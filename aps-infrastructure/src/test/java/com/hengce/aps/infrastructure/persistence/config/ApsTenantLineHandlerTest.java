package com.hengce.aps.infrastructure.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.tenant.TenantContext;
import java.util.UUID;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 租户 SQL 拦截处理器测试。
 */
class ApsTenantLineHandlerTest {

    private static final UUID OTHER_TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldReturnDefaultTenantExpression() {
        ApsTenantLineHandler handler = new ApsTenantLineHandler();

        StringValue value = (StringValue) handler.getTenantId();

        assertEquals(TenantContext.DEFAULT_TENANT_ID.toString(), value.getValue());
    }

    @Test
    void shouldReturnExplicitTenantExpression() {
        TenantContext.setTenantId(OTHER_TENANT);
        ApsTenantLineHandler handler = new ApsTenantLineHandler();

        StringValue value = (StringValue) handler.getTenantId();

        assertEquals(OTHER_TENANT.toString(), value.getValue());
    }

    @Test
    void shouldIgnoreTablesWithoutTenantColumn() {
        ApsTenantLineHandler handler = new ApsTenantLineHandler();

        assertTrue(handler.ignoreTable("tenant"));
        assertTrue(handler.ignoreTable("ROLE_PERMISSION"));
        assertTrue(handler.ignoreTable("user_role"));
        assertFalse(handler.ignoreTable("orders"));
        assertFalse(handler.ignoreTable(null));
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }
}
