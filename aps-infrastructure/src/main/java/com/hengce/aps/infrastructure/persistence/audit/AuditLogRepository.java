package com.hengce.aps.infrastructure.persistence.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审计日志追加写入，对应 V1 的 {@code audit_log}（追加只读，无 updated_at/deleted）。
 *
 * <p>{@code audit_log} 不参与 MyBatis 实体映射，使用 {@link JdbcTemplate} 显式携带 {@code tenant_id}，
 * 避免依赖租户拦截器。{@code before_value}/{@code after_value} 仅记录面向审计的字段快照。</p>
 */
@Repository
public class AuditLogRepository {

    /**
     * JSON 序列化器；调用方只传字符串/数字等可序列化值，避免 Java 8 时间类型依赖。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条审计记录。
     *
     * @param tenantId 租户标识
     * @param action 动作编码，如 ORDER_FROZEN / ORDER_RELEASED
     * @param resourceType 资源类型，如 ORDER
     * @param resourceId 资源业务标识，如订单号
     * @param beforeValue 变更前字段快照，可为空
     * @param afterValue 变更后字段快照，可为空
     */
    public void record(UUID tenantId, String action, String resourceType, String resourceId,
                       Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        String sql = """
                INSERT INTO audit_log (tenant_id, user_id, action, resource_type, resource_id,
                                       before_value, after_value)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """;
        jdbcTemplate.update(sql, tenantId, AuditContext.getCurrentUserId(), action, resourceType,
                resourceId, toJson(beforeValue), toJson(afterValue));
    }

    private static String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计快照序列化失败", exception);
        }
    }
}
