package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.service.strategy.StrategyRuleView;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.ScheduledTask;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveEnvelopeMapper;
import com.hengce.aps.solver.api.SolveHandle;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolveResult;
import com.hengce.aps.solver.api.SolveStatus;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 排产服务：DB 装配 → 求解 → 结果落库 → 归因回填。
 *
 * <p>M2-SE-03 起本类只负责“执行”与指标归类，{@code solve_task} 状态流转由
 * {@link SolveTaskLifecycleService} 统一管理；{@link #submit(SolveRequest)} 保留原同步语义
 * 以兼容既有调用方，异步入口 {@code POST /solve-tasks} 走生命周期服务。</p>
 *
 * <p>M2-SE-06 引擎异常/超时/不可用降级：失败时在 metrics 写入
 * {@code engineFallback=true}、{@code engineFallbackReason=TIMEOUT|ENGINE_ERROR|SOLVER_UNAVAILABLE}、
 * {@code fallbackMode=HARD_ONLY}、{@code fallbackAt}；DSL/参数校验失败只记
 * {@code validationFailure=true}，不降级、不写引擎降级标记。</p>
 */
@Service
public class ScheduleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleService.class);

    /**
     * JSON 序列化器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * M2 唯一可用的降级模式：只保证硬约束。
     */
    private static final String FALLBACK_MODE = "HARD_ONLY";

    /**
     * 求解锁争用失败消息前缀；与 {@code TimefoldSolverAdapter} 的等待锁失败文案保持一致，
     * 仅用于把“容量问题”与“引擎异常”区分开，不改变既有失败语义。
     */
    private static final String LOCK_CONTENTION_MESSAGE_PREFIX = "求解器正被其他求解任务占用";

    /**
     * 引擎级“移动线程”指标键；由适配器写入 {@code SolveMetrics.extra}，这里原样透传，
     * 避免调用方只看到请求值而误以为多线程已生效。
     */
    private static final List<String> MOVE_THREAD_METRIC_KEYS = List.of(
            "moveThreadCountRequested",
            "moveThreadCountResolved",
            "moveThreadCountEffective",
            "moveThreadCountApplied",
            "moveThreadCountIgnored",
            "multiThreadedAvailable");

    /**
     * 软约束权重在 {@code SolveConfig.parameters} 中的键；适配器据此激活权重。
     *
     * <p>激活/关闭由引擎适配器在 {@code SolverFactory.create} 前后完成，本类只负责注入权重，
     * 不直接调用权重注册表，避免两处同时激活/清除互相覆盖。</p>
     */
    public static final String SOFT_WEIGHTS_KEY = "softWeights";

    /**
     * 软约束规则条数（C19）。
     */
    private static final int SOFT_RULE_COUNT = 8;

    /**
     * 软约束权重合计（C19）。
     */
    private static final int SOFT_WEIGHT_TOTAL = 100;

    /**
     * 策略服务，用于读取当前生效策略的软规则权重。
     */
    private final StrategyService strategyService;

    /**
     * 引擎路由表。
     */
    private final Map<SolverEngine, SolverAdapter> adapters;

    /**
     * 问题装配器。
     */
    private final ScheduleProblemAssembler assembler;

    /**
     * 结果写入器。
     */
    private final ScheduleResultWriter resultWriter;

    /**
     * 求解任务持久层。
     */
    private final SolveTaskRepository solveTaskRepository;

    /**
     * 是否写入引擎降级标记。
     */
    private final boolean fallbackEnabled;

    /**
     * 引擎路由唯一解析入口。
     */
    private final EngineRouter engineRouter;

    /**
     * 构造排产服务。
     *
     * @param solverAdapters 已装配的求解适配器
     * @param assembler 问题装配器
     * @param resultWriter 结果写入器
     * @param solveTaskRepository 求解任务持久层
     * @param strategyService 策略服务，读取软规则权重
     * @param fallbackEnabled 引擎降级开关
     * @param ortoolsEnabled OR-Tools 实现开关（M2 占位实现默认关闭）
     */
    public ScheduleService(List<SolverAdapter> solverAdapters, ScheduleProblemAssembler assembler,
                           ScheduleResultWriter resultWriter, SolveTaskRepository solveTaskRepository,
                           StrategyService strategyService,
                           @Value("${aps.solve.fallback-enabled:true}") boolean fallbackEnabled,
                           @Value("${aps.solve.engine.ortools-enabled:false}") boolean ortoolsEnabled) {
        Map<SolverEngine, SolverAdapter> registry = new EnumMap<>(SolverEngine.class);
        for (SolverAdapter adapter : solverAdapters) {
            registry.put(adapter.engine(), adapter);
        }
        this.adapters = registry;
        this.assembler = assembler;
        this.resultWriter = resultWriter;
        this.solveTaskRepository = solveTaskRepository;
        this.strategyService = strategyService;
        this.fallbackEnabled = fallbackEnabled;
        this.engineRouter = new EngineRouter(strategyService, fallbackEnabled, ortoolsEnabled);
    }

    /**
     * 解析引擎路由（M2-SE-06 唯一入口）：请求级优先于策略级，缺省 Timefold。
     *
     * @param request 求解请求
     * @return 路由决策
     */
    public EngineRoute route(SolveRequest request) {
        return engineRouter.resolve(request);
    }

    /**
     * 查找引擎适配器。
     *
     * @param engine 引擎类型
     * @return 适配器，未装配时为空
     */
    public SolverAdapter adapter(SolverEngine engine) {
        return adapters.get(engine);
    }

    /**
     * 提前终止引擎侧异步求解（best-effort）。
     *
     * <p>M2 的 Timefold 适配器未实现异步句柄，调用会抛
     * {@link UnsupportedOperationException}；由生命周期层以线程中断兜底，不视为失败。</p>
     *
     * @param engine 引擎类型
     * @param handle 异步句柄
     */
    public void terminateEarly(SolverEngine engine, SolveHandle handle) {
        SolverAdapter adapter = adapters.get(engine);
        if (adapter == null) {
            return;
        }
        try {
            adapter.terminateEarly(handle);
        } catch (UnsupportedOperationException ignored) {
            // 引擎未实现异步终止，生命周期层用 Future.cancel(true) 兜底。
        } catch (RuntimeException ignored) {
            // 终止失败不应影响取消接口的成功语义。
        }
    }

    /**
     * 生成引擎中立配置。
     *
     * @param request 求解请求
     * @param engine 引擎类型
     * @return 求解配置
     */
    public SolveConfig toConfig(SolveRequest request, SolverEngine engine) {
        SolveConfig config = SolveEnvelopeMapper.toConfig(request, engine);
        Map<String, Integer> weights = resolveSoftWeights(request.strategyId());
        if (weights.isEmpty()) {
            return config;
        }
        Map<String, Object> parameters = new LinkedHashMap<>(
                config.parameters() == null ? Map.of() : config.parameters());
        parameters.put(SOFT_WEIGHTS_KEY, weights);
        return new SolveConfig(config.engine(), config.timeoutSeconds(), config.moveThreadCount(),
                config.acceptor(), config.randomSeed(), config.maximumStepCount(),
                config.unimprovedSecondsSpentLimit(), config.direction(),
                config.useFrozenAssignments(), Map.copyOf(parameters));
    }

    /**
     * 读取当前生效策略的 8 条软规则权重。
     *
     * <p>无策略、策略不存在或规则不满足“8 条且合计 100”时不注入权重；适配器在无权重时
     * 显式关闭软约束、仅保留硬约束，本类不构造默认权重。激活/关闭由适配器负责，本类不触碰权重注册表。</p>
     *
     * @param strategyId 策略主键，可空
     * @return 权重映射；空表示不注入
     */
    private Map<String, Integer> resolveSoftWeights(UUID strategyId) {
        if (strategyId == null) {
            LOGGER.debug("求解请求未指定策略，本次求解仅硬约束");
            return Map.of();
        }
        StrategyView strategy;
        try {
            strategy = strategyService.get(strategyId);
        } catch (BizException exception) {
            LOGGER.warn("策略 {} 不可用，不注入软约束权重: {}", strategyId, exception.getMessage());
            return Map.of();
        }
        if (strategy == null || strategy.rules() == null || strategy.rules().isEmpty()) {
            LOGGER.warn("策略 {} 无规则明细，不注入软约束权重", strategyId);
            return Map.of();
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (StrategyRuleView rule : strategy.rules()) {
            if (rule == null || rule.code() == null || rule.weight() == null
                    || Boolean.FALSE.equals(rule.enabled())
                    || !"SOFT".equalsIgnoreCase(rule.type())) {
                continue;
            }
            weights.put(rule.code(), rule.weight().intValue());
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (weights.size() != SOFT_RULE_COUNT || total != SOFT_WEIGHT_TOTAL) {
            LOGGER.warn("策略 {} 软规则权重不合规（条数={}, 合计={}），不注入权重",
                    strategyId, weights.size(), total);
            return Map.of();
        }
        return weights;
    }

    /**
     * 受理前的订单存在性校验：请求点名但库里查不到的订单号直接 400，不静默漏排。
     *
     * <p>与 {@link ScheduleProblemAssembler#validatePinnedOrderNos} 是两条独立校验，互不替代。</p>
     *
     * @param request 求解请求
     */
    public void requireExistingOrderNos(SolveRequest request) {
        assembler.requireExistingOrderNos(request);
    }

    /**
     * 兼容旧调用的同步提交。
     *
     * @param request 求解请求
     * @return 求解结果
     */
    public SolveResult submit(SolveRequest request) {
        EngineRoute route = route(request);
        requireExistingOrderNos(request);
        ScheduleProblemAssembler.validatePinnedOrderNos(request.orderNos(), request.parameters());
        SolverAdapter adapter = adapters.get(route.effectiveEngine());
        SolveConfig config = SolveEnvelopeMapper.toConfig(request, route.effectiveEngine());
        UUID tenantId = TenantContext.getTenantId();
        UUID sandboxId = request.sandboxId() == null
                ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();
        long solveTaskId = solveTaskRepository.create(tenantId, sandboxId, request.strategyId(),
                route.effectiveEngine().getCode().toUpperCase(Locale.ROOT), toJson(config), null, "RUNNING");
        ExecutionOutcome outcome = mergeRouting(execute(request, solveTaskId, config, adapter,
                () -> false, (phase, progress, bestScore) -> { }), route);
        solveTaskRepository.finish(tenantId, solveTaskId, outcome.status().name(),
                outcome.scoreHard(), outcome.scoreSoft(), toJson(outcome.metrics()), outcome.message());
        if (outcome.validationFailure()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, outcome.message());
        }
        SolveStatus legacyStatus = outcome.status() == SolveTaskStatus.SOLVED
                ? SolveStatus.SUCCESS : SolveStatus.FAILED;
        return new SolveResult(request.taskNo(), legacyStatus, outcome.tasks(), outcome.message());
    }

    /**
     * 执行一次求解（不改变 solve_task 状态，供异步生命周期调用）。
     *
     * @param request 求解请求
     * @param solveTaskId 求解任务主键，用于结果落库与归因回填
     * @param config 求解配置
     * @param adapter 引擎适配器，可空（视为引擎不可用）
     * @param stopSignal 取消/超时信号，落库前检查
     * @param progressSink 阶段回调
     * @return 执行结果
     */
    public ExecutionOutcome execute(SolveRequest request, long solveTaskId, SolveConfig config,
                                    SolverAdapter adapter, StopSignal stopSignal, ProgressSink progressSink) {
        long startNanos = System.nanoTime();
        progressSink.update("ASSEMBLING", 0.05, null);
        ScheduleProblemAssembler.AssembledProblem assembled;
        try {
            assembled = assembler.assemble(request, config);
        } catch (BizException exception) {
            long durationMs = elapsedMillis(startNanos);
            Map<String, Object> metrics = baseMetrics(config, null, durationMs);
            if (exception.getErrorCode() == ErrorCode.UNPROCESSABLE_ENTITY) {
                metrics.put("validationFailure", true);
                metrics.put("phase", "FAILED");
                metrics.put("progress", 1.0);
                return new ExecutionOutcome(SolveTaskStatus.FAILED, null, null, metrics,
                        exception.getMessage(), true, List.of());
            }
            return fallbackOrFailed(metrics, "ENGINE_ERROR", exception.getMessage(), null, List.of());
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startNanos);
            return fallbackOrFailed(baseMetrics(config, null, durationMs), "ENGINE_ERROR",
                    safeMessage(exception), null, List.of());
        }

        if (stopSignal.shouldStop()) {
            return stopped(config, null, startNanos);
        }
        if (adapter == null) {
            long durationMs = elapsedMillis(startNanos);
            return fallbackOrFailed(baseMetrics(config, null, durationMs), "SOLVER_UNAVAILABLE",
                    "求解引擎未装配: " + engineCode(config), null, List.of());
        }

        // 在 try 外声明：完整性守卫失败时要回填该解的分数与已排位数（不写降级标记）。
        ScheduleSolution solution = null;
        try {
            progressSink.update("SOLVING", 0.10, null);
            solution = adapter.solve(assembled.problem(), config);
            long durationMs = elapsedMillis(startNanos);
            Map<String, Object> metrics = baseMetrics(config, solution, durationMs);
            // 保留 null 值：metrics.bestScore 必须恒存在（C20），值可为 null；
            // 详情装配侧用 null 友好的不可变拷贝，不再因显式 null 抛 NPE。
            metrics.put("bestScore", bestScore(solution));
            if (solution == null) {
                return fallbackOrFailed(metrics, "ENGINE_ERROR", "求解器未返回结果", null, List.of());
            }
            // C20 §24.2：期望任务数 = Σ ScheduleProblem.orders[*].operations.size()，
            // 装配阶段被剔除的订单进入 excludedOrders 且不计入（当前装配对无路线订单直接拒绝，
            // 不存在剔除分支，故为空数组）。
            int expectedTaskCount = assembled.expectedTaskCount();
            metrics.put("expectedTaskCount", expectedTaskCount);
            metrics.put("assignedTaskCount", solution.assignments().size());
            metrics.put("excludedOrders", List.of());
            if (!"SOLVED".equalsIgnoreCase(solution.status())) {
                String reason = "TIMEOUT".equalsIgnoreCase(solution.status()) ? "TIMEOUT" : "ENGINE_ERROR";
                String failure = solution.message() == null ? "求解未得到可行解" : solution.message();
                return fallbackOrFailed(metrics, reason, failure, solution, List.of());
            }
            if (stopSignal.shouldStop()) {
                return stopped(config, solution, startNanos);
            }

            // 完整性前置校验：分数只能说明“没有硬惩罚”，不能保证“任务数全排满”。
            int assignedTaskCount = solution.assignments().size();
            if (assignedTaskCount != expectedTaskCount) {
                return failed(metrics,
                        "求解结果不完整：期望 " + expectedTaskCount + " 个任务，实际排位 "
                                + assignedTaskCount + " 个；拒绝落库（既有任务保持原样）",
                        solution);
            }

            progressSink.update("PERSISTING", 0.90, bestScore(solution));
            ScheduleResultWriter.WriteOutcome writeOutcome = resultWriter.persist(assembled, solution, solveTaskId);
            metrics.put("persistedTaskCount", writeOutcome.taskCount());
            metrics.put("justificationCount", writeOutcome.justificationCount());
            metrics.put("phase", "DONE");
            metrics.put("progress", 1.0);
            progressSink.update("DONE", 1.0, bestScore(solution));
            SolveResult base = SolveEnvelopeMapper.toResult(request, solution);
            return new ExecutionOutcome(SolveTaskStatus.SOLVED, score(solution, true), score(solution, false),
                    metrics, enrichMessage(base.message(), solution), false, base.tasks());
        } catch (ScheduleIntegrityException exception) {
            // 数据完整性守卫（如整表重叠）失败：引擎正常返回，是落库前最后一道闸门拦下了计划。
            // 按契约记 FAILED/FAILED 并保留明细，不写 engineFallback*/degraded（没走降级路径）。
            Map<String, Object> guardMetrics = baseMetrics(config, solution, elapsedMillis(startNanos));
            guardMetrics.put("bestScore", bestScore(solution));
            guardMetrics.put("expectedTaskCount", assembled.expectedTaskCount());
            guardMetrics.put("assignedTaskCount", solution == null ? 0 : solution.assignments().size());
            guardMetrics.put("excludedOrders", List.of());
            return failed(guardMetrics, exception.getMessage(), solution);
        } catch (BizException exception) {
            long durationMs = elapsedMillis(startNanos);
            Map<String, Object> metrics = baseMetrics(config, null, durationMs);
            if (exception.getErrorCode() == ErrorCode.UNPROCESSABLE_ENTITY) {
                metrics.put("validationFailure", true);
                metrics.put("phase", "FAILED");
                metrics.put("progress", 1.0);
                return new ExecutionOutcome(SolveTaskStatus.FAILED, null, null, metrics,
                        exception.getMessage(), true, List.of());
            }
            return fallbackOrFailed(metrics, "ENGINE_ERROR", exception.getMessage(), null, List.of());
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startNanos);
            if (stopSignal.shouldStop()) {
                return stopped(config, null, startNanos);
            }
            boolean timedOut = durationMs >= timeoutBudgetMillis(config);
            return fallbackOrFailed(baseMetrics(config, null, durationMs),
                    timedOut ? "TIMEOUT" : "ENGINE_ERROR", safeMessage(exception), null, List.of());
        }
    }

    private ExecutionOutcome stopped(SolveConfig config, ScheduleSolution solution, long startNanos) {
        Map<String, Object> metrics = baseMetrics(config, solution, elapsedMillis(startNanos));
        metrics.put("phase", "CANCELLED");
        metrics.put("progress", 1.0);
        return new ExecutionOutcome(SolveTaskStatus.CANCELLED,
                solution == null ? null : score(solution, true),
                solution == null ? null : score(solution, false),
                metrics, "任务已终止", false, List.of());
    }

    private ExecutionOutcome fallbackOrFailed(Map<String, Object> metrics, String reason, String message,
                                              ScheduleSolution solution, List<ScheduledTask> tasks) {
        SolveTaskStatus status = "TIMEOUT".equals(reason) ? SolveTaskStatus.TIMEOUT : SolveTaskStatus.FAILED;
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        if (solution == null) {
            // 没有结果对象时返回体 tasks 必为空数组，已排位数也是确定值 0（不是“没算”）；
            // persistedTaskCount 已由 baseMetrics 恒写 0，这里只补这一个与“返回了什么”相关的键。
            metrics.putIfAbsent("assignedTaskCount", 0);
        }
        if (fallbackEnabled) {
            metrics.put("engineFallback", true);
            metrics.put("engineFallbackReason", reason);
            metrics.put("engineFallbackDetail", fallbackDetail(reason, message));
            metrics.put("fallbackMode", FALLBACK_MODE);
            metrics.put("fallbackAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        }
        return new ExecutionOutcome(status,
                solution == null ? null : score(solution, true),
                solution == null ? null : score(solution, false),
                metrics, message, false, tasks);
    }

    /**
     * 明确的失败（不写降级标记）：用于“结果口径不完整/不可用”等非时间、非引擎异常场景。
     *
     * <p>C20 §24.2：{@code expectedTaskCount ≠ assignedTaskCount} 时终态必须是
     * {@code FAILED}、{@code errorCode=FAILED}，message 同时给出两个数；不得记
     * {@code ENGINE_ERROR}（否则监控里“引擎炸了”和“结果缺任务”无法区分）。</p>
     *
     * @param metrics 执行指标
     * @param message 失败说明
     * @param solution 求解结果，可空
     * @return 执行结果
     */
    private ExecutionOutcome failed(Map<String, Object> metrics, String message,
                                    ScheduleSolution solution) {
        metrics.put("phase", "FAILED");
        metrics.put("progress", 1.0);
        return new ExecutionOutcome(SolveTaskStatus.FAILED,
                solution == null ? null : score(solution, true),
                solution == null ? null : score(solution, false),
                metrics, message, false, List.of());
    }

    /**
     * 区分降级的具体成因：配置问题（目标引擎不可用）与容量问题（求解锁争用）不得混为同一个原因。
     *
     * @param reason 降级原因
     * @param message 引擎返回的说明
     * @return 降级细分
     */
    private static String fallbackDetail(String reason, String message) {
        if ("SOLVER_UNAVAILABLE".equals(reason)) {
            return EngineRoute.DETAIL_SOLVER_NOT_ASSEMBLED;
        }
        if ("TIMEOUT".equals(reason)) {
            return EngineRoute.DETAIL_SOLVER_TIMEOUT;
        }
        if (message != null && message.startsWith(LOCK_CONTENTION_MESSAGE_PREFIX)) {
            return EngineRoute.DETAIL_LOCK_CONTENTION;
        }
        return EngineRoute.DETAIL_ENGINE_EXCEPTION;
    }

    /**
     * 把路由决策合并进执行指标；校验失败只记录决策、不写降级标记（C28）。
     *
     * @param outcome 执行结果
     * @param route 路由决策
     * @return 合并后的执行结果
     */
    static ExecutionOutcome mergeRouting(ExecutionOutcome outcome, EngineRoute route) {
        Map<String, Object> metrics = new LinkedHashMap<>(outcome.metrics());
        if (outcome.validationFailure()) {
            route.applyDecision(metrics);
        } else {
            route.applyFallback(metrics);
        }
        return new ExecutionOutcome(outcome.status(), outcome.scoreHard(), outcome.scoreSoft(),
                metrics, outcome.message(), outcome.validationFailure(), outcome.tasks());
    }

    private static long timeoutBudgetMillis(SolveConfig config) {
        return Math.max(1, config.timeoutSeconds()) * 1000L;
    }

    private static String engineCode(SolveConfig config) {
        return config.engine() == null ? "TIMEFOLD" : config.engine().toUpperCase(Locale.ROOT);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static Long score(ScheduleSolution solution, boolean hard) {
        ScoreFact score = solution.score();
        if (score == null) {
            return null;
        }
        java.math.BigDecimal value = hard ? score.hardScore() : score.softScore();
        return value == null ? null : value.longValue();
    }

    private static String bestScore(ScheduleSolution solution) {
        if (solution == null || solution.score() == null) {
            return null;
        }
        return solution.score().scoreText();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static Map<String, Object> baseMetrics(SolveConfig config, ScheduleSolution solution, long durationMs) {
        Map<String, Object> extra = solution == null || solution.metrics() == null
                || solution.metrics().extra() == null ? Map.of() : solution.metrics().extra();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("durationMs", solution == null || solution.metrics() == null
                ? durationMs : solution.metrics().durationMs());
        metrics.put("totalDurationMs", durationMs);
        metrics.put("taskCount", solution == null ? 0 : solution.assignments().size());
        metrics.put("engine", engineCode(config));
        metrics.put("calendarFallback", Boolean.TRUE.equals(extra.get("calendarFallback")));
        metrics.put("dependencyFallback", Boolean.TRUE.equals(extra.get("dependencyFallback")));
        metrics.put("setupMatrixFallback", Boolean.TRUE.equals(extra.get("setupMatrixFallback")));
        // 未落库时也必须显式给出 0，避免“字段缺失”被读成“未知”。
        metrics.put("persistedTaskCount", 0);
        for (String key : List.of("unassignedTaskCount", "discardedBestSolution")) {
            if (extra.containsKey(key)) {
                metrics.put(key, extra.get(key));
            }
        }
        // 引擎级口径：移动线程“请求值 vs 生效值”必须对调用方可见，不能“看起来生效了其实没用”。
        for (String key : MOVE_THREAD_METRIC_KEYS) {
            if (extra.containsKey(key)) {
                metrics.put(key, extra.get(key));
            }
        }
        if (solution != null && solution.metrics() != null) {
            metrics.put("stepCount", solution.metrics().stepCount());
            if (solution.metrics().terminationReason() != null) {
                metrics.put("terminationReason", solution.metrics().terminationReason());
            }
            metrics.put("unimprovedStepCount", solution.metrics().unimprovedStepCount());
        }
        return metrics;
    }

    private static String enrichMessage(String message, ScheduleSolution solution) {
        Map<String, Object> extra = solution.metrics() == null || solution.metrics().extra() == null
                ? Map.of() : solution.metrics().extra();
        String flags = "降级标记: calendarFallback=" + Boolean.TRUE.equals(extra.get("calendarFallback"))
                + ", dependencyFallback=" + Boolean.TRUE.equals(extra.get("dependencyFallback"))
                + ", setupMatrixFallback=" + Boolean.TRUE.equals(extra.get("setupMatrixFallback"));
        return message == null || message.isBlank() ? flags : message + "；" + flags;
    }

    /**
     * JSON 序列化。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    /**
     * 取消/超时信号。
     */
    public interface StopSignal {

        /**
         * 是否应停止并放弃结果落库。
         *
         * @return {@code true} 表示已取消或超时
         */
        boolean shouldStop();
    }

    /**
     * 阶段进度回调。
     */
    public interface ProgressSink {

        /**
         * 更新阶段进度。
         *
         * @param phase 阶段
         * @param progress 进度 0.0–1.0
         * @param bestScore 最优评分，可空
         */
        void update(String phase, double progress, String bestScore);
    }

    /**
     * 单次执行结果。
     *
     * @param status 终态
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metrics 指标
     * @param message 说明
     * @param validationFailure 是否为校验失败（不降级）
     * @param tasks 求解结果任务（仅同步兼容路径使用）
     */
    public record ExecutionOutcome(
            SolveTaskStatus status,
            Long scoreHard,
            Long scoreSoft,
            Map<String, Object> metrics,
            String message,
            boolean validationFailure,
            List<ScheduledTask> tasks
    ) {
    }
}
