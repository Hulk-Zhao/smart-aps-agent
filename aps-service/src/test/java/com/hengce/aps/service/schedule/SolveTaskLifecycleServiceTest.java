package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 求解任务生命周期单测：PENDING/RUNNING、取消、超时与引擎异常降级、幂等、同步校验 422。
 *
 * <p>使用内存假仓库替代真库，调度线程与看门狗仍为真实实现，覆盖并发终态写入的幂等性。</p>
 */
class SolveTaskLifecycleServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void submitShouldEnterPendingOrRunningAndFinishSolved() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(outcome(SolveTaskStatus.SOLVED));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        assertTrue(List.of("PENDING", "RUNNING").contains(submitted.status()), submitted.status());

        SolveTaskDetail finished = awaitTerminal(lifecycle, submitted.taskId(), 3000);
        assertEquals(SolveTaskStatus.SOLVED.name(), finished.status());
        assertEquals(submitted.taskId(), finished.resultTaskId());
        verify(scheduleService, timeout(1000)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void idempotencyKeyShouldReturnExistingTaskWithoutSecondSolve() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        long existingId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", "key-1", "PENDING");
        repository.finish(TENANT_ID, existingId, "SOLVED", 0L, -10L, "{\"phase\":\"DONE\"}", "ok");
        ScheduleService scheduleService = mockScheduleService(60);
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail returned = lifecycle.submit(request(), "key-1");

        assertEquals(existingId, returned.taskId());
        assertEquals(SolveTaskStatus.SOLVED.name(), returned.status());
        verify(scheduleService, never()).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void cancelShouldStopRunningTaskAndKeepCancelledTerminal() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        CountDownLatch solving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    solving.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        assertTrue(solving.await(3, TimeUnit.SECONDS), "求解应已开始");
        awaitStatus(lifecycle, submitted.taskId(), SolveTaskStatus.RUNNING, 2000);

        SolveTaskDetail cancelled = lifecycle.cancel(submitted.taskId());
        assertEquals(SolveTaskStatus.CANCELLED.name(), cancelled.status());

        release.countDown();
        Thread.sleep(200L);
        assertEquals(SolveTaskStatus.CANCELLED.name(), lifecycle.detail(submitted.taskId()).status());
        verify(scheduleService).terminateEarly(any(), any());
    }

    @Test
    void engineExceptionShouldFallbackWithMarker() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("timefold crashed"));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 3000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertTrue(failed.degraded());
        assertEquals("ENGINE_ERROR", failed.metrics().get("engineFallbackReason"));
        assertEquals("HARD_ONLY", failed.metrics().get("fallbackMode"));
        assertNotNull(failed.metrics().get("fallbackAt"));
    }

    @Test
    void watchdogShouldTimeoutAndFallback() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail timedOut = awaitTerminal(lifecycle, submitted.taskId(), 6000);

        assertEquals(SolveTaskStatus.TIMEOUT.name(), timedOut.status());
        assertTrue(timedOut.degraded());
        assertEquals("TIMEOUT", timedOut.metrics().get("engineFallbackReason"));
        release.countDown();
    }

    @Test
    void validationFailureInSyncModeShouldThrow422WithoutFallback() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("validationFailure", true);
        metrics.put("phase", "FAILED");
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new ScheduleService.ExecutionOutcome(SolveTaskStatus.FAILED, null, null,
                        metrics, "租户下没有可用资源，无法排产", true, List.of()));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, false, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(SolveTaskStatus.FAILED.name(), repository.rows.values().iterator().next().status());
        assertTrue(repository.rows.values().iterator().next().metricsJson().contains("validationFailure"));
    }

    @Test
    void submitWithUnknownSandboxShouldReturnNotFoundInsteadOfForeignKey500() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        repository.markSandboxMissing();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals(0L, repository.countActiveBySandbox(TENANT_ID, SANDBOX_ID));
    }

    @Test
    void submitShouldMapConcurrentSandboxDeletionToNotFoundInsteadOf500() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        repository.markSandboxDeletedConcurrently();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void detailShouldTolerateNullValuedMetricsOnFailedTask() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", null, "PENDING");
        repository.finish(TENANT_ID, taskId, "FAILED", null, null,
                "{\"phase\":null,\"progress\":1.0,\"bestScore\":null,\"engineFallback\":true,"
                        + "\"engineFallbackReason\":null,\"fallbackMode\":\"HARD_ONLY\"}", "引擎异常");
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        SolveTaskDetail detail = lifecycle.detail(taskId);

        assertEquals("FAILED", detail.status());
        assertEquals("FAILED", detail.phase());
        assertEquals("FAILED", detail.errorCode());
        assertNull(detail.bestScore());
        assertTrue(detail.metrics().containsKey("bestScore"), "metrics 应保留原 JSON 的 null 键");
        assertNull(detail.metrics().get("bestScore"));
        assertTrue(detail.degraded());
    }

    private static SolveTaskLifecycleService lifecycle(FakeSolveTaskRepository repository,
                                                      ScheduleService scheduleService,
                                                      boolean asyncEnabled,
                                                      boolean fallbackEnabled) {
        SolveProgressPublisher publisher = new SolveProgressPublisher(event -> { }, "none");
        ConstraintJustificationRepository justificationRepository = new ConstraintJustificationRepository(null);
        return new SolveTaskLifecycleService(repository, justificationRepository, scheduleService, publisher,
                asyncEnabled, fallbackEnabled, 8, 0);
    }

    private static ScheduleService mockScheduleService(int timeoutSeconds) {
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.route(any())).thenReturn(new EngineRoute("timefold",
                EngineRoute.SOURCE_DEFAULT, SolverEngine.TIMEFOLD, SolverEngine.TIMEFOLD,
                false, null, null, null));
        when(scheduleService.toConfig(any(), any())).thenReturn(config(timeoutSeconds));
        return scheduleService;
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static SolveRequest request() {
        return new SolveRequest("T-1", SANDBOX_ID, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleService.ExecutionOutcome outcome(SolveTaskStatus status) {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        return new ScheduleService.ExecutionOutcome(status, 0L, -10L, metrics, "ok", false, List.of());
    }

    private static SolveTaskDetail awaitTerminal(SolveTaskLifecycleService lifecycle, long taskId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            SolveTaskDetail detail = lifecycle.detail(taskId);
            if (SolveTaskStatus.fromNullable(detail.status()).isTerminal()) {
                return detail;
            }
            Thread.sleep(20L);
        }
        fail("任务未在 " + timeoutMillis + " ms 内进入终态");
        return null;
    }

    private static void awaitStatus(SolveTaskLifecycleService lifecycle, long taskId, SolveTaskStatus expected,
                                    long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.name().equals(lifecycle.detail(taskId).status())) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("任务未在 " + timeoutMillis + " ms 内进入 " + expected);
    }

    /**
     * 内存假仓库，行为对齐 SolveTaskRepository 的条件终态语义。
     */
    private static final class FakeSolveTaskRepository extends SolveTaskRepository {

        private final ConcurrentMap<Long, SolveTaskRecord> rows = new ConcurrentHashMap<>();

        private final ConcurrentMap<String, Long> idempotencyKeys = new ConcurrentHashMap<>();

        private final AtomicLong sequence = new AtomicLong();

        private boolean sandboxMissing;

        private boolean sandboxFkViolation;

        void markSandboxMissing() {
            this.sandboxMissing = true;
        }

        void markSandboxDeletedConcurrently() {
            this.sandboxFkViolation = true;
        }

        @Override
        public boolean sandboxExists(UUID tenantId, UUID sandboxId) {
            return !sandboxMissing;
        }

        private FakeSolveTaskRepository() {
            super(null);
        }

        @Override
        public long create(UUID tenantId, UUID sandboxId, UUID strategyId, String engine, String configJson,
                           String idempotencyKey, String status) {
            if (sandboxFkViolation) {
                throw new DataIntegrityViolationException("insert violates fk_solve_task_sandbox",
                        new SQLException("ERROR: insert or update on table \"solve_task\" violates foreign key "
                                + "constraint \"fk_solve_task_sandbox\""));
            }
            long id = sequence.incrementAndGet();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime startedAt = "RUNNING".equals(status) ? now : null;
            rows.put(id, new SolveTaskRecord(id, tenantId, sandboxId, strategyId, engine, status,
                    null, null, "{}", null, idempotencyKey, startedAt, null, now, now));
            if (idempotencyKey != null) {
                idempotencyKeys.put(tenantId + ":" + idempotencyKey, id);
            }
            return id;
        }

        @Override
        public Optional<SolveTaskRecord> findById(UUID tenantId, long solveTaskId) {
            SolveTaskRecord row = rows.get(solveTaskId);
            return row == null || !row.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(row);
        }

        @Override
        public Optional<SolveTaskRecord> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
            Long id = idempotencyKeys.get(tenantId + ":" + idempotencyKey);
            return id == null ? Optional.empty() : findById(tenantId, id);
        }

        @Override
        public int markRunning(UUID tenantId, long solveTaskId) {
            SolveTaskRecord row = rows.get(solveTaskId);
            if (row == null || !"PENDING".equals(row.status())) {
                return 0;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            rows.put(solveTaskId, new SolveTaskRecord(row.id(), row.tenantId(), row.sandboxId(), row.strategyId(),
                    row.engine(), "RUNNING", row.scoreHard(), row.scoreSoft(), row.metricsJson(),
                    row.errorMessage(), row.idempotencyKey(), now, null, row.createdAt(), now));
            return 1;
        }

        @Override
        public int mergeMetrics(UUID tenantId, long solveTaskId, String metricsJson) {
            return 1;
        }

        @Override
        public int finish(UUID tenantId, long solveTaskId, String status, Long scoreHard, Long scoreSoft,
                          String metricsJson, String errorMessage) {
            SolveTaskRecord row = rows.get(solveTaskId);
            if (row == null || !List.of("PENDING", "RUNNING").contains(row.status())) {
                return 0;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            rows.put(solveTaskId, new SolveTaskRecord(row.id(), row.tenantId(), row.sandboxId(), row.strategyId(),
                    row.engine(), status, scoreHard, scoreSoft, metricsJson, errorMessage,
                    row.idempotencyKey(), row.startedAt(), now, row.createdAt(), now));
            return 1;
        }

        @Override
        public long countActiveBySandbox(UUID tenantId, UUID sandboxId) {
            return rows.values().stream()
                    .filter(row -> row.sandboxId().equals(sandboxId))
                    .filter(row -> List.of("PENDING", "RUNNING").contains(row.status()))
                    .count();
        }
    }
}
