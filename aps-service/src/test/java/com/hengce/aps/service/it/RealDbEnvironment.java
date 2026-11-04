package com.hengce.aps.service.it;

/**
 * 真库集成测试连接参数，全部从环境变量读取，不落任何会被提交的配置文件。
 *
 * <p>启用开关：{@code APS_TEST_DB_ENABLED=true}。未启用时所有真库测试由 JUnit 条件跳过，
 * 保证无数据库的 CI/开发环境仍可正常执行单测。</p>
 *
 * <p>连接参数优先使用 {@code APS_TEST_DB_URL}；未提供时按 host/port/name/user/password
 * 拼装 JDBC URL，默认值对齐 {@code scripts/local-db-up.ps1} 的便携 PostgreSQL
 * （127.0.0.1:55432/aps_local，账号 aps/aps_dev_pwd）。</p>
 */
final class RealDbEnvironment {

    private static final String ENABLED = "APS_TEST_DB_ENABLED";

    private static final String URL = "APS_TEST_DB_URL";

    private static final String HOST = "APS_TEST_DB_HOST";

    private static final String PORT = "APS_TEST_DB_PORT";

    private static final String NAME = "APS_TEST_DB_NAME";

    private static final String USER = "APS_TEST_DB_USER";

    private static final String PASSWORD = "APS_TEST_DB_PASSWORD";

    private static final String DEFAULT_HOST = "127.0.0.1";

    private static final String DEFAULT_PORT = "55432";

    private static final String DEFAULT_NAME = "aps_local";

    private static final String DEFAULT_USER = "aps";

    private static final String DEFAULT_PASSWORD = "aps_dev_pwd";

    private RealDbEnvironment() {
    }

    static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv(ENABLED));
    }

    static String jdbcUrl() {
        String explicit = System.getenv(URL);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        return "jdbc:postgresql://" + value(HOST, DEFAULT_HOST) + ":" + value(PORT, DEFAULT_PORT)
                + "/" + value(NAME, DEFAULT_NAME);
    }

    static String username() {
        return value(USER, DEFAULT_USER);
    }

    static String password() {
        return value(PASSWORD, DEFAULT_PASSWORD);
    }

    private static String value(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
