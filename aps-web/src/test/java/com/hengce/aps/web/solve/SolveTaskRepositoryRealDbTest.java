package com.hengce.aps.web.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M2-SE-03 / M2-SE-05 真库验收：连接本机便携 PostgreSQL 的 aps_m2_se3 库。
 *
 * <p>库不可用时整类跳过（{@link Assumptions}），不影响共享工作区其他模块构建。
 * 覆盖 V6 幂等唯一索引、条件终态写入、JSONB metrics 合并、归因 {@code task_ids} 解析与补齐。</p>
 */
class SolveTaskRepositoryRealDbTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SANDBOX_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000401");

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/aps_m2_se3";

    private JdbcTemplate jdbcTemplate;

    private SolveTaskRepository repository;

    private ConstraintJustificationRepository justificationRepository;

    private final List<Long> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(JDBC_URL, "aps", "aps_dev_pwd");
        dataSource.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        try {
            template.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException exception) {
            Assumptions.assumeTrue(false, "aps_m2_se3 不可用，跳过真库验收: "
                    + exception.getMessage());
        }
        this.jdbcTemplate = template;
        this.repository = new SolveTaskRepository(template);
        this.justificationRepository = new ConstraintJustificationRepository(template);
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate == null) {
            return;
        }
        for (Long taskId : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM solve_task WHERE id = ?", taskId);
        }
        createdTaskIds.clear();
    }

    @Test
    void v6IdempotencyIndexRejectsDuplicateKey() {
        String key = "it-idem-" + UUID.randomUUID();
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD",
                "{}", key, "PENDING");
        createdTaskIds.add(taskId);

        DuplicateKeyException duplicate = assertThrows(DuplicateKeyException.class, () ->
                repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", key, "PENDING"));
        assertTrue(duplicate.getMessage().contains("uq_solve_task_idempotency")
                        || duplicate.getMessage().contains("uq_solve_task_sandbox_idempotency"),
                "唯一索引未生效: " + duplicate.getMessage());

        SolveTaskRepository.SolveTaskRecord existing =
                repository.findByIdempotencyKey(TENANT_ID, key).orElseThrow();
        assertEquals(taskId, existing.id());
        assertEquals("PENDING", existing.status());
    }

    @Test
    void finishIsConditionalSoCancelDoesNotOverwriteSolved() {
        long taskId = createTask("PENDING");
        assertEquals(1, repository.markRunning(TENANT_ID, taskId));
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId, "{\"phase\":\"SOLVING\"}"));

        assertEquals(1, repository.finish(TENANT_ID, taskId, "SOLVED", 0L, -120L,
                "{\"bestScore\":\"0hard/-120soft\"}", null));
        assertEquals(0, repository.finish(TENANT_ID, taskId, "CANCELLED", null, null,
                "{\"errorCode\":\"CANCELLED\"}", "用户取消"),
                "已是终态的任务不应被取消覆盖");

        SolveTaskRepository.SolveTaskRecord record =
                repository.findById(TENANT_ID, taskId).orElseThrow();
        assertEquals("SOLVED", record.status());
        assertEquals(0L, record.scoreHard());
        assertEquals(-120L, record.scoreSoft());
        assertTrue(record.metricsJson().contains("bestScore"));
        assertTrue(record.metricsJson().contains("SOLVING"));
    }

    @Test
    void metricsMergeKeepsExistingKeys() {
        long taskId = createTask("PENDING");
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId,
                "{\"progress\":0.4,\"phase\":\"SOLVING\"}"));
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId, "{\"progress\":0.9}"));

        String metrics = repository.findById(TENANT_ID, taskId).orElseThrow().metricsJson();
        assertTrue(metrics.contains("SOLVING"), metrics);
        assertTrue(metrics.contains("0.9"), metrics);
    }

    @Test
    void justificationTaskIdsAreParsedAndBackfilled() {
        long taskId = createTask("SOLVED");
        UUID filledId = insertJustification(taskId,
                "[{\"type\":\"TASK\",\"id\":101},{\"type\":\"TASK\",\"taskId\":\"102\"}]");
        UUID emptyId = insertJustification(taskId, "[]");

        List<ConstraintJustificationRepository.JustificationRow> rows =
                justificationRepository.findBySolveTaskId(TENANT_ID, taskId);
        assertEquals(2, rows.size());
        List<Long> filledTaskIds = rows.stream()
                .filter(row -> row.id().equals(filledId))
                .findFirst().orElseThrow().taskIds();
        assertEquals(List.of(101L, 102L), filledTaskIds);
        assertTrue(justificationRepository.hasTaskIds(TENANT_ID, taskId));
        assertTrue(rows.stream().filter(row -> row.id().equals(emptyId))
                .findFirst().orElseThrow().taskIds().isEmpty());

        assertEquals(1, justificationRepository.backfillTaskIds(TENANT_ID, emptyId,
                List.of(103L)));
        List<Long> backfilled = justificationRepository.findBySolveTaskId(TENANT_ID, taskId)
                .stream().filter(row -> row.id().equals(emptyId))
                .findFirst().orElseThrow().taskIds();
        assertEquals(List.of(103L), backfilled);
        assertFalse(backfilled.isEmpty());
    }

    private long createTask(String status) {
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD",
                "{}", null, status);
        createdTaskIds.add(taskId);
        return taskId;
    }

    private UUID insertJustification(long taskId, String factsJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO constraint_justification
                    (id, tenant_id, solve_task_id, constraint_id, constraint_name,
                     score_impact, implicated_facts, explanation_zh)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, TENANT_ID, taskId, "C-RESOURCE-NO-OVERLAP", "资源不重叠",
                "HARD", factsJson, "真库验收种子");
        return id;
    }
}
