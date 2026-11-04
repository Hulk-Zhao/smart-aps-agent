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
     * 构造排产服务。
     *
     * @param solverAdapters 已装配的求解适配器
     * @param assembler 问题装配器
     * @param resultWriter 结果写入器
     * @param solveTaskRepository 求解任务持久层
     * @param strategyService 策略服务，读取软规则权重
     * @param fallbackEnabled 引擎降级开关
     */
    public ScheduleService(List<SolverAdapter> solverAdapters, ScheduleProblemAssembler assembler,
                           ScheduleResultWriter resultWriter, SolveTaskRepository solveTaskRepository,
                           StrategyService strategyService,
                           @Value("${aps.solve.fallback-enabled:true}") boolean fallbackEnabled) {
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
    }

    /**
     * 解析引擎编码。
     *
     * @param engineCode 引擎编码
     * @return 引擎类型
     */
    public SolverEngine resolveEngine(String engineCode) {
        try {
            return SolverEngine.fromCode(engineCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
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
     * <p>无策略、策略不存在或规则不满足“8 条且合计 100”时不注入权重，交由适配器使用默认权重，
     * 不在此处构造默认权重。激活/关闭由适配器负责，本类不触碰权重注册表。</p>
     *
     * @param strategyId 策略主键，可空
     * @return 权重映射；空表示不注入
     */
    private Map<String, Integer> resolveSoftWeights(UUID strategyId) {
        if (strategyId == null) {
            LOGGER.debug("求解请求未指定策略，不注入软约束权重，使用引擎默认权重");
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
     * 兼容旧调用的同步提交。
     *
     * @param request 求解请求
     * @return 求解结果
     */
    public SolveResult submit(SolveRequest request) {
        SolverEngine engine = resolveEngine(request.engine());
        ScheduleProblemAssembler.validatePinnedOrderNos(request.orderNos(), request.parameters());
        SolverAdapter adapter = adapters.get(engine);
        if (adapter == null) {
            throw new BizException(ErrorCode.SOLVER_NOT_AVAILABLE, "求解引擎未装配: " + engine.getCode());
        }
        SolveConfig config = SolveEnvelopeMapper.toConfig(request, engine);
        UUID tenantId = TenantContext.getTenantId();
        UUID sandboxId = request.sandboxId() == null
                ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();
        long solveTaskId = solveTaskRepository.create(tenantId, sandboxId, request.strategyId(),
                engine.getCode().toUpperCase(Locale.ROOT), toJson(config), null, "RUNNING");
        ExecutionOutcome outcome = execute(request, solveTaskId, config, adapter,
                () -> false, (phase, progress, bestScore) -> { });
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

        try {
            progressSink.update("SOLVING", 0.10, null);
            ScheduleSolution solution = adapter.solve(assembled.problem(), config);
            long durationMs = elapsedMillis(startNanos);
            Map<String, Object> metrics = baseMetrics(config, solution, durationMs);
            metrics.put("bestScore", bestScore(solution));
            if (solution == null) {
                return fallbackOrFailed(metrics, "ENGINE_ERROR", "求解器未返回结果", null, List.of());
            }
            if (!"SOLVED".equalsIgnoreCase(solution.status())) {
                String reason = "TIMEOUT".equalsIgnoreCase(solution.status()) ? "TIMEOUT" : "ENGINE_ERROR";
                String failure = solution.message() == null ? "求解未得到可行解" : solution.message();
                return fallbackOrFailed(metrics, reason, failure, solution, List.of());
            }
            if (stopSignal.shouldStop()) {
                return stopped(config, solution, startNanos);
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
        if (fallbackEnabled) {
            metrics.put("engineFallback", true);
            metrics.put("engineFallbackReason", reason);
            metrics.put("fallbackMode", FALLBACK_MODE);
            metrics.put("fallbackAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        }
        return new ExecutionOutcome(status,
                solution == null ? null : score(solution, true),
                solution == null ? null : score(solution, false),
                metrics, message, false, tasks);
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
        if (solution != null && solution.metrics() != null) {
            metrics.put("stepCount", solution.metrics().stepCount());
            metrics.put("terminationReason", solution.metrics().terminationReason());
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
