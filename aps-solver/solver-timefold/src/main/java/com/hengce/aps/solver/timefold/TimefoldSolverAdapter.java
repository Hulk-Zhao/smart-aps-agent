package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Timefold Solver 适配器。
 *
 * <p>M1-SE-03 在 M1-SE-02 的同步求解之上接入 C12 约束能力：资源可用时段、工序依赖与滞后、
 * 换型矩阵、辅助资源占用，并在结果中输出降级标记与约束归因。
 * M2 在此接入 8 条核心软约束：{@code SolveConfig.parameters} 携带 {@code softWeights} 时，
 * 必须在 {@code SolverFactory.create(...)} <b>之前</b> {@link SoftConstraintWeights#activate(Map)}
 * ——{@link ApsConstraintProvider} 在构造时读取 {@code isEnabled()}，工厂建完再激活无效。</p>
 *
 * <p><b>并发保护</b>：{@link SoftConstraintWeights} 是进程级静态注册表，{@code disable()} 会清空
 * 整个进程的权重。因此 {@code activate → SolverFactory.create → solve → disable} 全程由
 * {@link #SOLVER_LOCK} 独占；拿不到锁时直接返回 {@code FAILED}，绝不带着其他任务的权重静默求解。
 * 工厂每次求解新建（本例未缓存），避免权重被首次配置固化。</p>
 *
 * <p><b>缺字段 → 规则静默不生效</b>（输入由 {@code TimefoldModelBuilder} 从订单级回填到任务级）：</p>
 *
 * <table border="1">
 *   <caption>软约束输入字段与缺字段行为</caption>
 *   <tr><th>规则</th><th>输入字段</th><th>缺字段时期望行为</th></tr>
 *   <tr><td>MIN_TARDINESS</td><td>{@code dueDate}</td><td>无交期不产生超期惩罚（该任务规则不生效）</td></tr>
 *   <tr><td>EDD</td><td>{@code dueDate}（相邻两任务）</td><td>任一方无交期则判定为不违反（不罚）</td></tr>
 *   <tr><td>SPT</td><td>{@code runSeconds}</td><td>不依赖新增字段</td></tr>
 *   <tr><td>SAME_PRODUCT_CONTINUOUS</td><td>{@code productFamily}（相邻两任务）</td>
 *       <td>任一方为空则判定为不违反，同品连续规则不生效</td></tr>
 *   <tr><td>MIN_SETUP_TIME</td><td>{@code setupSeconds}</td><td>不依赖新增字段；无换型为 0</td></tr>
 *   <tr><td>BALANCE_LOAD</td><td>{@code runSeconds}</td><td>不依赖新增字段</td></tr>
 *   <tr><td>PRIORITY_RESPECT</td><td>{@code priority}（相邻两任务）</td>
 *       <td>为空按 {@code NORMAL}，仅保持最轻档位，不会误罚</td></tr>
 *   <tr><td>MIN_MAKESPAN</td><td>{@code startTime/endTime}</td><td>不依赖新增字段</td></tr>
 * </table>
 */
public class TimefoldSolverAdapter implements SolverAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimefoldSolverAdapter.class);

    /**
     * 引擎编码（外部小写）。
     */
    private static final String ENGINE_CODE = "timefold";

    /**
     * 失败兜底说明。
     */
    private static final String FAILED_MESSAGE = "Timefold 求解失败";

    /**
     * 软约束权重在 {@code SolveConfig.parameters} 中的键，与 {@code ScheduleService} 对齐。
     */
    private static final String SOFT_WEIGHTS_KEY = "softWeights";

    /**
     * 进程级求解互斥；覆盖 activate → 建工厂 → 求解 → disable，避免权重注册表互相覆盖。
     */
    private static final ReentrantLock SOLVER_LOCK = new ReentrantLock();

    /**
     * 等待求解锁的最长秒数；再长就直接失败，避免把请求拖到求解超时。
     */
    private static final int LOCK_WAIT_SECONDS = 5;

    @Override
    public SolverEngine engine() {
        return SolverEngine.TIMEFOLD;
    }

    @Override
    public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
        Objects.requireNonNull(problem, "problem 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        long beginNanos = System.nanoTime();
        int waitSeconds = Math.min(LOCK_WAIT_SECONDS, Math.max(1, config.timeoutSeconds()));
        boolean locked;
        try {
            locked = SOLVER_LOCK.tryLock(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedSolution("等待求解器锁被中断，未执行求解", elapsedMillis(beginNanos));
        }
        if (!locked) {
            return failedSolution("求解器正被其他求解任务占用，等待 " + waitSeconds
                    + " 秒仍未释放，请稍后重试", elapsedMillis(beginNanos));
        }
        try {
            Map<String, Integer> weights = resolveSoftWeights(config);
            if (!weights.isEmpty()) {
                SoftConstraintWeights.activate(weights);
            } else {
                LOGGER.debug("未提供 softWeights，本次求解仅硬约束");
            }
            if (problem.orders().isEmpty() && problem.frozenAssignments().isEmpty()) {
                return emptySolution(beginNanos);
            }
            SchedulePlan plan = TimefoldModelBuilder.build(problem);
            if (plan.getTasks().isEmpty()) {
                return emptySolution(beginNanos);
            }
            SolverFactory<SchedulePlan> solverFactory = SolverFactory.create(solverConfig(config));
            Solver<SchedulePlan> solver = solverFactory.buildSolver();
            SchedulePlan best = solver.solve(plan);
            if (best == null) {
                return failedSolution("Timefold 未返回结果", elapsedMillis(beginNanos));
            }
            return TimefoldModelBuilder.toSolution(
                    best, elapsedMillis(beginNanos), ConstraintJustifications.from(best));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failedSolution(exception.getMessage(), elapsedMillis(beginNanos));
        } finally {
            SoftConstraintWeights.disable();
            SOLVER_LOCK.unlock();
        }
    }

    /**
     * 从 {@code SolveConfig.parameters} 读取软约束权重。
     *
     * <p>只有存在 {@value #SOFT_WEIGHTS_KEY} 时才启用软约束；缺省即本次仅硬约束，
     * 不在此处构造默认权重（避免"假装有策略"）。权重合法性由
     * {@link SoftConstraintWeights#activate(Map)} 校验（8 条且合计 100）。</p>
     *
     * @param config 求解配置
     * @return 权重映射；空表示本次不启用软约束
     * @throws IllegalArgumentException 权重结构非法
     */
    private Map<String, Integer> resolveSoftWeights(SolveConfig config) {
        Map<String, Object> parameters = config.parameters();
        if (parameters == null) {
            return Map.of();
        }
        Object raw = parameters.get(SOFT_WEIGHTS_KEY);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 必须是规则编码到权重的映射");
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 不允许空规则编码");
            }
            weights.put(String.valueOf(entry.getKey()), toWeight(entry.getKey(), entry.getValue()));
        }
        return weights;
    }

    private int toWeight(Object code, Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 权重非整数: " + code + "=" + text);
            }
        }
        throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 权重非数值: " + code + "=" + value);
    }

    private SolverConfig solverConfig(SolveConfig config) {
        int seconds = Math.max(1, config.timeoutSeconds());
        TerminationConfig termination = new TerminationConfig()
                .withSpentLimit(Duration.ofSeconds(seconds));
        if (config.unimprovedSecondsSpentLimit() > 0) {
            termination.setUnimprovedSecondsSpentLimit((long) config.unimprovedSecondsSpentLimit());
        }
        if (config.maximumStepCount() > 0) {
            termination.setStepCountLimit((int) Math.min(Integer.MAX_VALUE, config.maximumStepCount()));
        }
        return new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(config.randomSeed())
                .withTerminationConfig(termination);
    }

    private ScheduleSolution emptySolution(long beginNanos) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", ENGINE_CODE);
        extra.put("calendarFallback", false);
        extra.put("dependencyFallback", false);
        extra.put("setupMatrixFallback", false);
        return new ScheduleSolution(
                "SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                List.of(),
                List.of(),
                new SolveMetrics(
                        elapsedMillis(beginNanos),
                        0L,
                        0,
                        "TIME_LIMIT",
                        List.of(),
                        0,
                        extra),
                "无待排任务");
    }

    private ScheduleSolution failedSolution(String message, long elapsedMs) {
        return new ScheduleSolution(
                "FAILED",
                null,
                List.of(),
                List.of(),
                new SolveMetrics(
                        elapsedMs,
                        0L,
                        0,
                        "TERMINATED",
                        List.of(),
                        0,
                        Map.of("engine", ENGINE_CODE)),
                message == null ? FAILED_MESSAGE : message);
    }

    private long elapsedMillis(long beginNanos) {
        return Duration.ofNanos(System.nanoTime() - beginNanos).toMillis();
    }
}
