package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
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
     * 归因扣分与软分不一致的固定日志标记；出现即为缺陷，不可当作噪音过滤。
     */
    private static final String JUSTIFICATION_SCORE_MISMATCH = "JUSTIFICATION_SCORE_MISMATCH";

    /**
     * 进程级求解互斥；覆盖 activate → 建工厂 → 求解 → disable，避免权重注册表互相覆盖。
     */
    private static final ReentrantLock SOLVER_LOCK = new ReentrantLock();

    /**
     * 等待求解锁的最长秒数；再长就直接失败，避免把请求拖到求解超时。
     */
    private static final int LOCK_WAIT_SECONDS = 5;

    /**
     * 判定“到达时限”的容差毫秒数：Timefold 的 {@code spentLimit} 结束时刻含少量收尾开销，
     * 实测为 +1.7%（60s 跑成 61.0s），容差取 1 秒足以区分“跑满时限”与“提前终止”。
     */
    private static final long TIME_LIMIT_TOLERANCE_MILLIS = 1000L;

    /**
     * Timefold 多线程求解的企业版服务类；社区版未打包该实现。
     *
     * <p>2.8.0 社区版下，{@code withMoveThreadCount} 只要不是 {@code NONE}（含 {@code "1"}、{@code "AUTO"}）
     * 都会在 {@code buildSolver()} 抛
     * {@code IllegalStateException: A commercial feature "Multi-threaded solving" was requested...}。
     * 契约的 {@code moveThreadCount=0} 默认取 {@code CPU-1}，若原样传进 {@code SolverConfig}，
     * 会让“不带 parameters 的默认请求”直接失败，因此必须做能力探测而非盲目透传。</p>
     */
    private static final String ENTERPRISE_SOLVER_SERVICE_CLASS =
            "ai.timefold.solver.enterprise.core.DefaultTimefoldSolverEnterpriseService";

    /**
     * 当前发行版是否具备多线程求解能力（社区版为 {@code false}）。
     */
    private static final boolean MULTI_THREADED_SOLVING_AVAILABLE = enterpriseServiceAvailable();

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
                // 显式关闭而不是"假设上次已清空"：只要本次请求没带权重，就必须回到 M1 仅硬约束，
                // 不能因注册表残留而静默沿用别人的权重。
                SoftConstraintWeights.disable();
                LOGGER.debug("未提供 softWeights，本次求解仅硬约束");
            }
            if (problem.orders().isEmpty() && problem.frozenAssignments().isEmpty()) {
                return emptySolution(beginNanos, config);
            }
            SchedulePlan plan = TimefoldModelBuilder.build(problem);
            if (plan.getTasks().isEmpty()) {
                return emptySolution(beginNanos, config);
            }
            SolverFactory<SchedulePlan> solverFactory = SolverFactory.create(solverConfig(config));
            Solver<SchedulePlan> solver = solverFactory.buildSolver();
            long solveBeginNanos = System.nanoTime();
            SchedulePlan best = solver.solve(plan);
            long engineMillis = Duration.ofNanos(System.nanoTime() - solveBeginNanos).toMillis();
            if (best == null) {
                return failedSolution("Timefold 未返回结果", elapsedMillis(beginNanos));
            }
            boolean timeLimitReached = engineMillis + TIME_LIMIT_TOLERANCE_MILLIS
                    >= Math.max(1, config.timeoutSeconds()) * 1000L;
            String terminationReason = timeLimitReached ? "TIME_LIMIT" : "TERMINATED";
            LOGGER.info("Timefold 求解结束: engineMs={} termination={} timeLimitReached={} bestScore={}",
                    engineMillis, terminationReason, timeLimitReached, best.getScore());
            return TimefoldModelBuilder.toSolution(
                    best,
                    elapsedMillis(beginNanos),
                    justifications(best, problem.solveTaskId()),
                    terminationReason,
                    timeLimitReached,
                    moveThreadAnnotations(config));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failedSolution(exception.getMessage(), elapsedMillis(beginNanos));
        } finally {
            SoftConstraintWeights.disable();
            SOLVER_LOCK.unlock();
        }
    }

    /**
     * 合并硬约束与软约束归因，并校验软约束重放扣分与最终软分一致。
     *
     * <p>软约束权重在 {@code finally} 中 {@code disable()}，本方法必须在关闭前调用，
     * 否则重放拿不到求解时生效的权重。</p>
     *
     * <p><strong>一致性失败信号：</strong>比对不一致时打 ERROR 级结构化日志并带固定标记
     * {@value #JUSTIFICATION_SCORE_MISMATCH}，字段为 solveTaskId / sumDeduction / expectedSoft / delta。
     * {@code delta = sumDeduction - expectedSoft}。该标记一旦出现即为缺陷（归因口径与目标函数分叉），
     * 不是日志噪音；但归因属于解释层，按契约不阻断求解落库，不抛异常、不改任务状态。</p>
     *
     * @param best 最优解
     * @param solveTaskId 求解任务主键，可空（试算）
     * @return 归因列表，硬约束在前
     */
    private List<ConstraintJustification> justifications(SchedulePlan best, Long solveTaskId) {
        List<ConstraintJustification> hard = ConstraintJustifications.from(best);
        List<ConstraintJustification> soft = SoftConstraintJustifications.from(best);
        if (!soft.isEmpty()) {
            long softScore = best.getScore() == null ? 0L : best.getScore().softScore();
            BigDecimal sumDeduction = soft.stream()
                    .map(ConstraintJustification::deduction)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expectedSoft = BigDecimal.valueOf(-softScore);
            BigDecimal delta = sumDeduction.subtract(expectedSoft);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                LOGGER.error("{} solveTaskId={} sumDeduction={} expectedSoft={} delta={}",
                        JUSTIFICATION_SCORE_MISMATCH, solveTaskId,
                        sumDeduction.toPlainString(), expectedSoft.toPlainString(), delta.toPlainString());
            }
        }
        List<ConstraintJustification> merged = new ArrayList<>(hard.size() + soft.size());
        merged.addAll(hard);
        merged.addAll(soft);
        return List.copyOf(merged);
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

    SolverConfig solverConfig(SolveConfig config) {
        int seconds = Math.max(1, config.timeoutSeconds());
        TerminationConfig termination = new TerminationConfig()
                .withSpentLimit(Duration.ofSeconds(seconds));
        if (config.unimprovedSecondsSpentLimit() > 0) {
            termination.setUnimprovedSecondsSpentLimit((long) config.unimprovedSecondsSpentLimit());
        }
        if (config.maximumStepCount() > 0) {
            termination.setStepCountLimit((int) Math.min(Integer.MAX_VALUE, config.maximumStepCount()));
        }
        ResolvedMoveThreads moveThreads = resolveMoveThreads(config.moveThreadCount());
        if (moveThreads.requested() > 1 && moveThreads.effective() <= 1) {
            LOGGER.warn("moveThreadCount 未被采用: 请求={} 解析={} 生效={} 原因={}",
                    config.moveThreadCount(), moveThreads.resolved(), moveThreads.effectiveText(),
                    moveThreads.reason());
        } else {
            LOGGER.info("Timefold 求解配置: 请求 moveThreadCount={} 解析={} 生效={} termination={} 可用核数={}",
                    config.moveThreadCount(), moveThreads.resolved(),
                    moveThreads.effectiveText() == null ? "DEFAULT" : moveThreads.effectiveText(),
                    termination, moveThreads.processors());
        }
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(config.randomSeed());
        if (moveThreads.effectiveText() != null) {
            // 只有“显式请求了非 0 值”才动这个配置项；未传/0 一律交给引擎默认。
            // 社区版（无 enterprise 实现）对任何非 NONE 值都会 buildSolver 抛异常，绝不透传数值。
            solverConfig.setMoveThreadCount(moveThreads.effectiveText());
        }
        return solverConfig.withTerminationConfig(termination);
    }

    /**
     * 合并移动线程的策略解析：未传/{@code 0} 不设置 {@code moveThreadCount}，走引擎默认；
     * 只有非 {@code 0} 值才尝试应用，并按发行版能力收敛。
     *
     * <p>没有企业版实现时，任何非 {@code NONE} 值（含 {@code "1"}）都会被 Timefold 拒绝，
     * 因此“生效值”统一为 {@code NONE}，并把被忽略的事实与原因写入指标。</p>
     *
     * @param requested 请求值（{@code 0} 表示 CPU-1，见契约 C20 {@code SolveConfig}）
     * @return 解析结果
     */
    ResolvedMoveThreads resolveMoveThreads(int requested) {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (requested <= 0) {
            // 未传/0：不设置 moveThreadCount，完全走引擎默认（社区版默认即单线程）。
            return new ResolvedMoveThreads(requested, Math.max(1, processors - 1), 1, null, processors,
                    "未传/0：不设置 moveThreadCount，走引擎默认（社区版即单线程）");
        }
        int resolved = Math.min(requested, processors);
        if (MULTI_THREADED_SOLVING_AVAILABLE && resolved > 1) {
            return new ResolvedMoveThreads(requested, resolved, resolved,
                    String.valueOf(resolved), processors, "企业版多线程求解可用");
        }
        if (MULTI_THREADED_SOLVING_AVAILABLE) {
            return new ResolvedMoveThreads(requested, resolved, 1, "NONE", processors,
                    "请求单线程，显式设置 NONE");
        }
        return new ResolvedMoveThreads(requested, resolved, 1, "NONE", processors,
                "Timefold 2.8.0 社区版未打包多线程求解（enterprise 服务类缺失），"
                        + "请求值被忽略，显式退回 NONE");
    }

    /**
     * 供响应 {@code metrics} 使用的移动线程口径，便于调用方看到“请求值 vs 生效值”。
     *
     * @param config 求解配置
     * @return 指标片段
     */
    Map<String, Object> moveThreadAnnotations(SolveConfig config) {
        ResolvedMoveThreads moveThreads = resolveMoveThreads(config.moveThreadCount());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("moveThreadCountRequested", moveThreads.requested());
        extra.put("moveThreadCountResolved", moveThreads.resolved());
        extra.put("moveThreadCountEffective",
                moveThreads.effectiveText() == null ? "DEFAULT" : moveThreads.effectiveText());
        extra.put("moveThreadCountApplied", moveThreads.effectiveText() != null);
        extra.put("moveThreadCountIgnored",
                moveThreads.requested() > 0 && moveThreads.resolved() > 1 && moveThreads.effective() <= 1);
        extra.put("multiThreadedAvailable", MULTI_THREADED_SOLVING_AVAILABLE);
        return extra;
    }

    /**
     * 探测企业版服务类是否可加载；加载失败即视为不具备多线程能力。
     *
     * @return 是否支持多线程求解
     */
    private static boolean enterpriseServiceAvailable() {
        try {
            Class.forName(ENTERPRISE_SOLVER_SERVICE_CLASS, false,
                    TimefoldSolverAdapter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private ScheduleSolution emptySolution(long beginNanos, SolveConfig config) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", ENGINE_CODE);
        extra.put("calendarFallback", false);
        extra.put("dependencyFallback", false);
        extra.put("setupMatrixFallback", false);
        extra.putAll(moveThreadAnnotations(config));
        return new ScheduleSolution(
                "SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                List.of(),
                List.of(),
                new SolveMetrics(
                        elapsedMillis(beginNanos),
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
                        "TERMINATED",
                        List.of(),
                        0,
                        Map.of("engine", ENGINE_CODE)),
                message == null ? FAILED_MESSAGE : message);
    }

    private long elapsedMillis(long beginNanos) {
        return Duration.ofNanos(System.nanoTime() - beginNanos).toMillis();
    }

    /**
     * 移动线程解析结果。
     *
     * @param requested 请求值（{@code 0} 表示契约默认 CPU-1）
     * @param resolved 按契约解析后的线程数
     * @param effective 实际生效线程数（能力缺失时恒为 1）
     * @param effectiveText 传给 {@code SolverConfig} 的文本值（{@code NONE} 或数字）
     * @param processors 可用核数
     * @param reason 生效值说明
     */
    record ResolvedMoveThreads(int requested, int resolved, int effective,
                               String effectiveText, int processors, String reason) {
    }
}
