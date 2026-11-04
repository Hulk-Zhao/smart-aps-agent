package com.hengce.aps.infrastructure.persistence.plan;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计划版本持久层（C24 / V3）。
 *
 * <p>{@code plan_version} 与 {@code task.plan_version_id} 由本类直接操作，
 * 使用 {@link JdbcTemplate} 并显式携带 {@code tenant_id}；发布后的快照只写一次，
 * 不提供任何覆盖已发布快照的方法。</p>
 */
@Repository
public class PlanVersionRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, sandbox_id, plan_no, version_no, status, snapshot::text AS snapshot,
                   task_count, published_at, published_by, rollback_of, reason, approver, version,
                   created_at
              FROM plan_version
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public PlanVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按主键查询未删除的计划版本。
     *
     * @param tenantId 租户标识
     * @param planId 计划版本主键（对外 planId）
     * @return 计划版本行
     */
    public Optional<PlanVersionRow> find(UUID tenantId, UUID planId) {
        List<PlanVersionRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ? AND deleted = false",
                ROW_MAPPER, tenantId, planId);
        return rows.stream().findFirst();
    }

    /**
     * 查询同一场景下指定版本号的已发布版本，用于回滚定位。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param versionNo 目标版本号
     * @return 目标版本行
     */
    public Optional<PlanVersionRow> findPublishedByVersionNo(UUID tenantId, UUID sandboxId, int versionNo) {
        List<PlanVersionRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND sandbox_id = ? AND version_no = ?"
                        + " AND status = 'PUBLISHED' AND deleted = false ORDER BY published_at DESC LIMIT 1",
                ROW_MAPPER, tenantId, sandboxId, String.valueOf(versionNo));
        return rows.stream().findFirst();
    }

    /**
     * 计算场景内下一个版本号（从 1 递增）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 下一个版本号
     */
    public int nextVersionNo(UUID tenantId, UUID sandboxId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(CAST(version_no AS INTEGER)), 0)
                  FROM plan_version
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                """, Integer.class, tenantId, sandboxId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 生成租户内当天下一个计划业务号 {@code PLAN-YYYYMMDD-NNN}。
     *
     * @param tenantId 租户标识
     * @param datePart 日期部分，如 {@code 20261104}
     * @return 计划业务号
     */
    public String nextPlanNo(UUID tenantId, String datePart) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM plan_version
                 WHERE tenant_id = ? AND plan_no LIKE ? AND deleted = false
                """, Integer.class, tenantId, "PLAN-" + datePart + "-%");
        long next = (count == null ? 0L : count.longValue()) + 1L;
        return "PLAN-" + datePart + "-" + String.format("%03d", next);
    }

    /**
     * 统计场景内已执行任务数（IN_PROGRESS/COMPLETED），用于回滚拦截。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 已执行任务数
     */
    public int countExecutedTasks(UUID tenantId, UUID sandboxId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM task
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                   AND status IN ('IN_PROGRESS', 'COMPLETED')
                """, Integer.class, tenantId, sandboxId);
        return count == null ? 0 : count;
    }

    /**
     * 统计场景内未删除的计划版本数，用于列表分页。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 版本总数
     */
    public long countBySandbox(UUID tenantId, UUID sandboxId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM plan_version
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                """, Long.class, tenantId, sandboxId);
        return count == null ? 0L : count;
    }

    /**
     * 分页查询场景内计划版本，按发布时间倒序、主键倒序稳定排序。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 版本行
     */
    public List<PlanVersionRow> pageBySandbox(UUID tenantId, UUID sandboxId, int offset, int limit) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false"
                        + " ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, sandboxId, limit, offset);
    }

    /**
     * 读取场景内全部任务，用于发布时固化快照。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 任务快照行，按订单号/工序/开始时间稳定排序
     */
    public List<Map<String, Object>> listTasksForSnapshot(UUID tenantId, UUID sandboxId) {
        return jdbcTemplate.query("""
                SELECT t.id::text AS task_id, o.order_no, t.operation_index, t.status,
                       t.start_time, t.end_time, CAST(t.resource_id AS text) AS resource_id, t.qty
                  FROM task t
                  LEFT JOIN orders o ON o.id = t.order_id AND o.deleted = false
                 WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                 ORDER BY o.order_no, t.operation_index, t.start_time
                """, (rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("taskId", rs.getString("task_id"));
                    row.put("orderNo", rs.getString("order_no"));
                    row.put("operationIndex", rs.getObject("operation_index"));
                    row.put("status", rs.getString("status"));
                    row.put("startTime", toText(rs.getObject("start_time", OffsetDateTime.class)));
                    row.put("endTime", toText(rs.getObject("end_time", OffsetDateTime.class)));
                    row.put("resourceId", rs.getString("resource_id"));
                    row.put("qty", rs.getObject("qty"));
                    return row;
                }, tenantId, sandboxId);
    }

    /**
     * 新建 DRAFT 计划版本（C32 apply 的步骤 4：先落 DRAFT，再在同一事务内走 C24 publish）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 产生该版本的场景
     * @param planNo 计划业务号
     * @param versionNo 场景内版本号
     * @param operator 操作人，可为空
     * @return 新版本主键（对外 planId）
     */
    public UUID insertDraftVersion(UUID tenantId, UUID sandboxId, String planNo, int versionNo, UUID operator) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,
                                          created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                """, id, tenantId, sandboxId, planNo, String.valueOf(versionNo), operator, operator);
        return id;
    }

    /**
     * 发布：仅在 DRAFT/REVIEW 状态可落 PUBLISHED，快照写一次。
     *
     * @param tenantId 租户标识
     * @param row 发布前的版本行
     * @param snapshotJson 快照 JSON
     * @param taskCount 快照任务数
     * @param operator 操作人，可为空
     * @param approver 审批人，可为空
     * @param reason 发布原因，可为空
     * @return 实际更新行数，0 表示状态已被并发修改
     */
    public int publish(UUID tenantId, PlanVersionRow row, String snapshotJson, int taskCount,
                       UUID operator, String approver, String reason) {
        String sql = """
                UPDATE plan_version
                   SET status = 'PUBLISHED', snapshot = ?::jsonb, task_count = ?, published_at = CURRENT_TIMESTAMP,
                       published_by = ?, approver = ?, reason = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND deleted = false AND status IN ('DRAFT', 'REVIEW')
                """;
        return jdbcTemplate.update(sql, snapshotJson, taskCount, operator, approver, reason, tenantId, row.id());
    }

    /**
     * 回滚：新建 PUBLISHED 版本，复制目标快照，不覆盖任何历史快照。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param planNo 新计划业务号
     * @param versionNo 新版本号
     * @param snapshotJson 目标快照 JSON
     * @param taskCount 快照任务数
     * @param operator 操作人，可为空
     * @param rollbackOf 被回滚的来源版本主键
     * @param reason 回滚原因，可为空
     * @return 新版本主键
     */
    public UUID insertRollbackVersion(UUID tenantId, UUID sandboxId, String planNo, int versionNo,
                                      String snapshotJson, int taskCount, UUID operator,
                                      UUID rollbackOf, String reason) {
        UUID newId = UUID.randomUUID();
        String sql = """
                INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,
                                          snapshot, task_count, published_at, published_by,
                                          rollback_of, reason, approver, version)
                VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?::jsonb, ?, CURRENT_TIMESTAMP, ?, ?, ?, NULL, 1)
                """;
        jdbcTemplate.update(sql, newId, tenantId, sandboxId, planNo, String.valueOf(versionNo), snapshotJson,
                taskCount, operator, rollbackOf, reason);
        return newId;
    }

    /**
     * 发布时把场景内全部任务回填到当前发布版本，使 {@code task.plan_version_id} 始终指向生效版本。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param planId 计划版本主键
     * @return 回填任务数
     */
    public int backfillTasks(UUID tenantId, UUID sandboxId, UUID planId) {
        String sql = """
                UPDATE task SET plan_version_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                """;
        return jdbcTemplate.update(sql, planId, tenantId, sandboxId);
    }

    /**
     * 回滚时把来源版本的任务指向新版本。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param fromPlanId 来源版本主键
     * @param toPlanId 新版本主键
     * @return 重指向任务数
     */
    public int repointTasks(UUID tenantId, UUID sandboxId, UUID fromPlanId, UUID toPlanId) {
        String sql = """
                UPDATE task SET plan_version_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false AND plan_version_id = ?
                """;
        return jdbcTemplate.update(sql, toPlanId, tenantId, sandboxId, fromPlanId);
    }

    private static String toText(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static final RowMapper<PlanVersionRow> ROW_MAPPER = (rs, rowNum) -> new PlanVersionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("sandbox_id", UUID.class),
            rs.getString("plan_no"),
            parseVersionNo(rs.getString("version_no")),
            rs.getString("status"),
            rs.getString("snapshot"),
            rs.getInt("task_count"),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getObject("published_by", UUID.class),
            rs.getObject("rollback_of", UUID.class),
            rs.getString("reason"),
            rs.getString("approver"),
            rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static int parseVersionNo(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * 计划版本行。
     *
     * @param id 主键（对外 planId）
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param planNo 计划业务号
     * @param versionNo 版本号
     * @param status 状态
     * @param snapshotJson 快照 JSON 文本
     * @param taskCount 快照任务数
     * @param publishedAt 发布时间
     * @param publishedBy 发布人
     * @param rollbackOf 回滚来源版本
     * @param reason 原因
     * @param approver 审批人
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     */
    public record PlanVersionRow(
            UUID id,
            UUID tenantId,
            UUID sandboxId,
            String planNo,
            int versionNo,
            String status,
            String snapshotJson,
            int taskCount,
            OffsetDateTime publishedAt,
            UUID publishedBy,
            UUID rollbackOf,
            String reason,
            String approver,
            int version,
            OffsetDateTime createdAt) {
    }
}
