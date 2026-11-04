package com.hengce.aps.infrastructure.persistence.audit;

import java.util.UUID;

/**
 * 线程级操作人上下文，供审计字段 created_by / updated_by 自动填充。
 *
 * <p>M1 尚未接入认证，过滤器/网关在完成鉴权后调用 {@link #setCurrentUserId(UUID)} 注入；
 * 未注入时保持 null（DDL 允许 created_by / updated_by 为空），不伪造系统账号。</p>
 */
public final class AuditContext {

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private AuditContext() {
    }

    /**
     * 读取当前操作人。
     *
     * @return 当前用户 id，未设置时返回 null
     */
    public static UUID getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 设置当前操作人。
     *
     * @param userId 用户 id；null 表示清除
     */
    public static void setCurrentUserId(UUID userId) {
        if (userId == null) {
            CURRENT_USER_ID.remove();
        } else {
            CURRENT_USER_ID.set(userId);
        }
    }

    /**
     * 清理当前线程操作人，必须在线程归还池前调用。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
