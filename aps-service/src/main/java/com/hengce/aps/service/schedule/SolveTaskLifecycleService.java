package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveHandle;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveProgress;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 求解任务生命周期服务（M2-SE-03）。
 *
 * <p>职责：受理异步求解、维护 {@code PENDING → RUNNING → 终态}、推送进度快照、
 * 处理取消与超时、引擎不可用/异常降级。执行本身委托给 {@link ScheduleService}，
 * 本类不复制问题装配与结果落库逻辑。</p>
 *
 * <p>并发约束：单工作线程 + 有界队列，保证每个场景同时最多 1 个 RUNNING；
 * 队列满时按引擎不可用降级为 FAILED 并写 {@code engineFallback} 标记。
 * 取消优先调用引擎 {@code terminateEarly}，引擎未实现异步句柄时以
 * {@link Future#cancel(boolean)} 线程中断兜底，并在落库前通过停止信号丢弃结果。</p>
 */
@Service
public class SolveTaskLifecycleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveTaskLifecycleService.class);

    /**
     * JSON 序列化器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 进度推送间隔（毫秒）。
     */
    private static final long PROGRESS_TICK_MILLIS = 500L;

    /**
     * M2 唯一降级模式。
     */
    private static final String FALLBACK_MODE = "HARD_ONLY";

    /**
     * 求解任务持久层。
     */
    private final SolveTaskRepository solveTaskRepository;

    /**
     * 归因持久层。
     */
    private final ConstraintJustificationRepository justificationRepository;

    /**
     * 排产执行服务。
     */
    private final ScheduleService scheduleService;

    /**
     * 进度发布器。
     */
    private final SolveProgressPublisher progressPublisher;

    /**
     * 工作线程池。
     */
    private final ThreadPoolExecutor worker;

    /**
     * 进度与看门狗调度器。
     */
    private final ScheduledExecutorService ticker;

    /**
     * 运行中任务：taskId → 运行上下文。
     */
    private final ConcurrentMap<Long, RunningTask> running = new ConcurrentHashMap<>();

    /**
     * 是否异步执行。
     */
    private final boolean asyncEnabled;

    /**
     * 是否写入引擎降级标记。
     */
    private final boolean fallbackEnabled;

    /**
     * 超时宽限秒数（用于看门狗，避免与引擎内部超时竞争）。
     */
    private final int timeoutGraceSeconds;

    /**
     * 构造生命周期服务。
     *
     * @param solveTaskRepository 求解任务持久层
     * @param justificationRepository 归因持久层
     * @param scheduleService 排产执行服务
     * @param progressPublisher 进度发布器
     * @param asyncEnabled 是否异步执行
     * @param fallbackEnabled 是否写降级标记
     * @param queueCapacity 有界队列容量
     * @param timeoutGraceSeconds 超时宽限秒数
     */
    public SolveTaskLifecycleService(SolveTaskRepository solveTaskRepository,
                                     ConstraintJustificationRepository justificationRepository,
                                     ScheduleService scheduleService,
                                     SolveProgressPublisher progressPublisher,
                                     @Value("${aps.solve.async-enabled:true}") boolean asyncEnabled,
                                     @Value("${aps.solve.fallback-enabled:true}") boolean fallbackEnabled,
                                     @Value("${aps.solve.queue-capacity:32}") int queueCapacity,
                                     @Value("${aps.solve.timeout-grace-seconds:5}") int timeoutGraceSeconds) {
        this.solveTaskRepository = solveTaskRepository;
        this.justificationRepository = justificationRepository;
        this.scheduleService = scheduleService;
        this.progressPublisher = progressPublisher;
        this.asyncEnabled = asyncEnabled;
        this.fallbackEnabled = fallbackEnabled;
        this.timeoutGraceSeconds = Math.max(0, timeoutGraceSeconds);
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "aps-solve-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aps-solve-progress");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 异步受理求解任务；相同幂等键返回既有任务。
     *
     * @param request 求解请求
     * @param idempotencyKey 幂等键，可空
     * @return 任务详情（PENDING 或已进入 RUNNING）
     */
    public SolveTaskDetail submit(SolveRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        UUID tenantId = TenantContext.getTenantId();
        EngineRoute route = scheduleService.route(request);
        ScheduleProblemAssembler.validatePinnedOrderNos(request.orderNos(), request.parameters());
        scheduleService.requireExistingOrderNos(request);
        SolveConfig config = scheduleService.toConfig(request, route.effectiveEngine());
        UUID sandboxId = request.sandboxId() == null ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();
        if (request.sandboxId() != null && !solveTaskRepository.sandboxExists(tenantId, sandboxId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId);
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key != null) {
            Optional<SolveTaskRecord> existing = solveTaskRepository.findByIdempotencyKey(tenantId, key);
            if (existing.isPresent()) {
                return detail(existing.get());
            }
        }

        long taskId;
        try {
            taskId = solveTaskRepository.create(tenantId, sandboxId, request.strategyId(),
                    engineCode(route.effectiveEngine()), toJson(config), key, "PENDING");
        } catch (DuplicateKeyException exception) {
            return solveTaskRepository.findByIdempotencyKey(tenantId, key)
                    .map(this::detail)
                    .orElseThrow(() -> new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "幂等键冲突，请使用新的 Idempotency-Key"));
        } catch (DataIntegrityViolationException exception) {
            if (isSandboxForeignKeyViolation(exception)) {
                throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId);
            }
            throw exception;
        }

        progressPublisher.publish(new SolveProgress(taskId, SolveTaskStatus.PENDING.name(), 0.0,
                "QUEUED", null, 0L, null, null, "已受理，等待求解"));
        RunningTask run = new RunningTask(taskId, tenantId, route, config);
        running.put(taskId, run);
        if (!asyncEnabled) {
            runSynchronously(run, request);
            return detail(taskId);
        }
        try {
            run.future = worker.submit(() -> executeTask(run, request));
        } catch (RejectedExecutionException exception) {
            running.remove(taskId);
            finishFallback(run, "SOLVER_UNAVAILABLE", "求解队列已满，请稍后重试");
        }
        return detail(taskId);
    }

    /**
     * 查询任务详情；运行中优先合并内存中的最新进度。
     *
     * @param taskId 求解任务主键
     * @return 任务详情
     */
    public SolveTaskDetail detail(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        SolveTaskRecord record = solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        return detail(record);
    }

    /**
     * 取消任务；已终态返回 40901，重复取消幂等返回当前详情。
     *
     * @param taskId 求解任务主键
     * @return 任务详情
     */
    public SolveTaskDetail cancel(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        SolveTaskRecord record = solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        SolveTaskStatus status = SolveTaskStatus.fromNullable(record.status());
        if (status == SolveTaskStatus.CANCELLED) {
            return detail(record);
        }
        if (status.isTerminal()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务已结束（" + status + "），无法取消");
        }

        RunningTask run = running.get(taskId);
        if (run != null) {
            run.cancelled.set(true);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("phase", "CANCELLED");
        metrics.put("progress", 1.0);
        metrics.put("cancelled", true);
        int updated = solveTaskRepository.finish(tenantId, taskId, SolveTaskStatus.CANCELLED.name(),
                null, null, toJson(metrics), "用户取消求解任务");
        if (updated > 0) {
            publishTerminal(taskId, SolveTaskStatus.CANCELLED, null, metrics, "用户取消求解任务");
        }
        if (run != null) {
            scheduleService.terminateEarly(run.route.effectiveEngine(),
                    new SolveHandle(run.handleId, run.route.effectiveEngine(), SolveTaskStatus.CANCELLED.name()));
            if (run.future != null) {
                run.future.cancel(true);
            }
            running.remove(taskId);
        }
        return detail(taskId);
    }

    /**
     * 读取归因并重放 task_ids（M2-SE-05）。
     *
     * @param taskId 求解任务主键
     * @return 归因行
     */
    public List<JustificationRow> justifications(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        return justificationRepository.findBySolveTaskId(tenantId, taskId);
    }

    private void runSynchronously(RunningTask run, SolveRequest request) {
        TenantContext.setTenantId(run.tenantId);
        try {
            if (solveTaskRepository.markRunning(run.tenantId, run.taskId) == 0) {
                return;
            }
            run.startedNanos = System.nanoTime();
            ScheduleService.ExecutionOutcome outcome = scheduleService.execute(request, run.taskId, run.config,
                    scheduleService.adapter(run.route.effectiveEngine()), () -> false,
                    (phase, progress, bestScore) -> publish(run, progress, phase, bestScore, phaseMessage(phase)));
            finishOutcome(run, outcome);
            if (outcome.validationFailure()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, outcome.message());
            }
        } catch (RuntimeException exception) {
            if (exception instanceof BizException) {
                throw exception;
            }
            finishFallback(run, "ENGINE_ERROR", safeMessage(exception));
        } finally {
            run.active.set(false);
            running.remove(run.taskId);
            TenantContext.clear();
        }
    }

    private void executeTask(RunningTask run, SolveRequest request) {
        TenantContext.setTenantId(run.tenantId);
        try {
            if (run.shouldStop()) {
                return;
            }
            if (solveTaskRepository.markRunning(run.tenantId, run.taskId) == 0) {
                return;
            }
            run.startedNanos = System.nanoTime();
            scheduleWatchdog(run);
            scheduleTicker(run);
            ScheduleService.ExecutionOutcome outcome = scheduleService.execute(request, run.taskId, run.config,
                    scheduleService.adapter(run.route.effectiveEngine()), run::shouldStop,
                    (phase, progress, bestScore) -> publish(run, progress, phase, bestScore, phaseMessage(phase)));
            finishOutcome(run, outcome);
        } catch (RuntimeException exception) {
            LOGGER.warn("求解任务 {} 执行异常", run.taskId, exception);
            finishFallback(run, "ENGINE_ERROR", safeMessage(exception));
        } finally {
            run.active.set(false);
            cancelFuture(run.tickerFuture);
            cancelFuture(run.watchdogFuture);
            running.remove(run.taskId);
            TenantContext.clear();
        }
    }

    private void scheduleTicker(RunningTask run) {
        run.tickerFuture = ticker.scheduleAtFixedRate(() -> {
            if (!run.active.get() || run.shouldStop()) {
                return;
            }
            long elapsed = elapsedMillis(run.startedNanos);
            long budget = Math.max(1L, run.config.timeoutSeconds()) * 1000L;
            double progress = 0.10 + 0.80 * Math.min(1.0, (double) elapsed / budget);
            publish(run, progress, run.lastPhase.get(), run.lastScore.get(),
                    "求解中，已耗时 " + elapsed + " ms");
        }, PROGRESS_TICK_MILLIS, PROGRESS_TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void scheduleWatchdog(RunningTask run) {
        int timeoutSeconds = Math.max(1, run.config.timeoutSeconds());
        run.watchdogFuture = ticker.schedule(() -> onTimeout(run),
                timeoutSeconds + timeoutGraceSeconds, TimeUnit.SECONDS);
    }

    private void onTimeout(RunningTask run) {
        if (!run.active.get() || run.shouldStop()) {
            return;
        }
        if (!run.timedOut.compareAndSet(false, true)) {
            return;
        }
        int timeoutSeconds = Math.max(1, run.config.timeoutSeconds());
        String message = "求解超时（超过 " + timeoutSeconds + " 秒）";
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", engineCode(run.route.effectiveEngine()));
        metrics.put("phase", SolveTaskStatus.TIMEOUT.name());
        metrics.put("progress", 1.0);
        metrics.put("timeoutSeconds", timeoutSeconds);
        // C20 §24.2：TIMEOUT / FAILED 的 persistedTaskCount 恒为 0。该路径没有结果对象、
        // 也没有调用落库，必须显式写“确认过的 0”，不能靠字段缺失被读成“未知”。
        metrics.put("persistedTaskCount", 0);
        // 没有返回任何任务，已排位数同样是确定值 0（返回体 tasks 恒为空数组）。
        metrics.put("assignedTaskCount", 0);
        applyFallback(metrics, "TIMEOUT");
        run.route.applyFallback(metrics);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId,
                SolveTaskStatus.TIMEOUT.name(), null, null, toJson(metrics), message);
        if (updated > 0) {
            publishTerminal(run.taskId, SolveTaskStatus.TIMEOUT, null, metrics, message);
        }
        scheduleService.terminateEarly(run.route.effectiveEngine(),
                new SolveHandle(run.handleId, run.route.effectiveEngine(), SolveTaskStatus.TIMEOUT.name()));
        if (run.future != null) {
            run.future.cancel(true);
        }
    }

    private void finishOutcome(RunningTask run, ScheduleService.ExecutionOutcome outcome) {
        Map<String, Object> metrics = new LinkedHashMap<>(outcome.metrics());
        if (outcome.validationFailure()) {
            run.route.applyDecision(metrics);
        } else {
            run.route.applyFallback(metrics);
        }
        metrics.putIfAbsent("phase",
                outcome.status() == SolveTaskStatus.SOLVED ? "DONE" : outcome.status().name());
        metrics.putIfAbsent("progress", 1.0);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId, outcome.status().name(),
                outcome.scoreHard(), outcome.scoreSoft(), toJson(metrics), outcome.message());
        if (updated > 0) {
            Long resultTaskId = outcome.status() == SolveTaskStatus.SOLVED ? run.taskId : null;
            publishTerminal(run.taskId, outcome.status(), resultTaskId, metrics, outcome.message());
        }
    }

    private void finishFallback(RunningTask run, String reason, String message) {
        SolveTaskStatus status = "TIMEOUT".equals(reason) ? SolveTaskStatus.TIMEOUT : SolveTaskStatus.FAILED;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", engineCode(run.route.effectiveEngine()));
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        // 同上：异常兜底路径未产生结果、未落库，落库行数与已排位数都是确定值 0。
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        applyFallback(metrics, reason);
        run.route.applyFallback(metrics);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId, status.name(),
                null, null, toJson(metrics), message);
        if (updated > 0) {
            publishTerminal(run.taskId, status, null, metrics, message);
        }
    }

    private void applyFallback(Map<String, Object> metrics, String reason) {
        if (!fallbackEnabled) {
            return;
        }
        metrics.put("engineFallback", true);
        metrics.put("engineFallbackReason", reason);
        metrics.put("fallbackMode", FALLBACK_MODE);
        metrics.put("fallbackAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    private void publish(RunningTask run, double progress, String phase, String bestScore, String message) {
        if (phase != null) {
            run.lastPhase.set(phase);
        }
        if (bestScore != null) {
            run.lastScore.set(bestScore);
        }
        progressPublisher.publish(new SolveProgress(run.taskId, SolveTaskStatus.RUNNING.name(),
                progress, phase, bestScore, elapsedMillis(run.startedNanos), null, null, message));
    }

    private void publishTerminal(long taskId, SolveTaskStatus status, Long resultTaskId,
                                 Map<String, Object> metrics, String message) {
        String bestScore = asString(metrics.get("bestScore"));
        String phase = asString(metrics.get("phase"));
        String errorCode = status == SolveTaskStatus.SOLVED ? null
                : asString(metrics.getOrDefault("engineFallbackReason", status.name()));
        long elapsed = 0L;
        SolveTaskRecord record = solveTaskRepository
                .findById(TenantContext.getTenantId(), taskId).orElse(null);
        if (record != null && record.startedAt() != null) {
            OffsetDateTime end = record.finishedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.finishedAt();
            elapsed = Math.max(0L, Duration.between(record.startedAt(), end).toMillis());
        }
        progressPublisher.publish(new SolveProgress(taskId, status.name(), 1.0,
                phase == null ? terminalPhase(status) : phase, bestScore, elapsed, resultTaskId,
                errorCode, message));
    }

    /**
     * 终态 {@code phase} 的兜底映射（C20）：{@code SOLVED} 必须是 {@code DONE}，其余与状态同名。
     *
     * @param status 终态
     * @return 契约枚举内的阶段值
     */
    private String terminalPhase(SolveTaskStatus status) {
        return status == SolveTaskStatus.SOLVED ? "DONE" : status.name();
    }

    private SolveTaskDetail detail(SolveTaskRecord record) {
        Map<String, Object> metrics = parseMetrics(record.metricsJson());
        SolveTaskStatus status = SolveTaskStatus.fromNullable(record.status());
        SolveProgress live = progressPublisher.latest(record.id()).orElse(null);
        double progress = status.isTerminal() ? 1.0
                : live != null ? live.progress() : asDouble(metrics.get("progress"), 0.0);
        String phase;
        if (live != null && !status.isTerminal()) {
            phase = live.phase();
        } else if (status == SolveTaskStatus.PENDING) {
            phase = "QUEUED";
        } else {
            phase = asString(metrics.get("phase"));
            if (phase == null) {
                phase = status.isTerminal() ? terminalPhase(status) : "QUEUED";
            }
        }
        String bestScore = live != null && live.bestScore() != null ? live.bestScore()
                : asString(metrics.get("bestScore"));
        long elapsed = 0L;
        if (record.startedAt() != null) {
            OffsetDateTime end = record.finishedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.finishedAt();
            elapsed = Math.max(0L, Duration.between(record.startedAt(), end).toMillis());
        }
        boolean degraded = Boolean.TRUE.equals(metrics.get("engineFallback"));
        String errorCode = null;
        if (status.isTerminal() && status != SolveTaskStatus.SOLVED) {
            String reason = degraded ? asString(metrics.get("engineFallbackReason")) : null;
            errorCode = reason == null ? status.name() : reason;
        }
        return new SolveTaskDetail(record.id(), record.sandboxId(), record.strategyId(), record.engine(),
                status.name(), progress, phase, bestScore, record.scoreHard(), record.scoreSoft(),
                elapsed, status == SolveTaskStatus.SOLVED ? record.id() : null,
                errorCode, record.errorMessage(), degraded, metrics,
                record.startedAt(), record.finishedAt(), record.createdAt(), record.updatedAt(),
                List.of());
    }

    /**
     * 判断是否为场景外键违反（预检通过但并发删除场景时仍会撞 FK）。
     *
     * @param exception 数据完整性异常
     * @return 约束名为 {@code fk_solve_task_sandbox} 时为 true
     */
    private static boolean isSandboxForeignKeyViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null && message.contains("fk_solve_task_sandbox");
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 长度不能超过 64");
        }
        return key;
    }

    private static String engineCode(SolverEngine engine) {
        return engine.getCode().toUpperCase(Locale.ROOT);
    }

    private static String phaseMessage(String phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case "ASSEMBLING" -> "装配求解问题";
            case "SOLVING" -> "引擎求解中";
            case "PERSISTING" -> "求解结果落库";
            case "DONE" -> "求解完成";
            default -> phase;
        };
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static long elapsedMillis(long startNanos) {
        if (startNanos == 0L) {
            return 0L;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static Map<String, Object> parseMetrics(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(metricsJson, new TypeReference<Map<String, Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * JSON 序列化。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    static String toJson(Object value) {
        return ScheduleService.toJson(value);
    }

    /**
     * 停止工作线程与调度器。
     */
    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
        ticker.shutdownNow();
    }

    /**
     * 运行上下文。
     */
    private static final class RunningTask {

        /**
         * 求解任务主键。
         */
        private final long taskId;

        /**
         * 租户标识。
         */
        private final UUID tenantId;

        /**
         * 引擎路由决策。
         */
        private final EngineRoute route;

        /**
         * 求解配置。
         */
        private final SolveConfig config;

        /**
         * 引擎异步句柄标识。
         */
        private final UUID handleId = UUID.randomUUID();

        /**
         * 是否已取消。
         */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /**
         * 是否已超时。
         */
        private final AtomicBoolean timedOut = new AtomicBoolean(false);

        /**
         * 是否仍活跃。
         */
        private final AtomicBoolean active = new AtomicBoolean(true);

        /**
         * 最近阶段。
         */
        private final AtomicReference<String> lastPhase = new AtomicReference<>("QUEUED");

        /**
         * 最近最优评分。
         */
        private final AtomicReference<String> lastScore = new AtomicReference<>();

        /**
         * 工作线程 Future。
         */
        private volatile Future<?> future;

        /**
         * 进度 ticker。
         */
        private volatile Future<?> tickerFuture;

        /**
         * 超时看门狗。
         */
        private volatile Future<?> watchdogFuture;

        /**
         * 开始求解的纳秒时间戳；0 表示尚未进入 RUNNING。
         */
        private volatile long startedNanos;

        private RunningTask(long taskId, UUID tenantId, EngineRoute route, SolveConfig config) {
            this.taskId = taskId;
            this.tenantId = tenantId;
            this.route = route;
            this.config = config;
        }

        private boolean shouldStop() {
            return cancelled.get() || timedOut.get();
        }
    }
}
