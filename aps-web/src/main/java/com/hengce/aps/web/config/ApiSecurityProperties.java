package com.hengce.aps.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M2 认证与限流配置（C27）。
 *
 * <p>所有密钥只从配置/环境变量读取，禁止入库、入日志。默认值为本地开发用的占位值，
 * 生产必须通过 {@code APS_SECURITY_JWT_SECRET} 等环境变量覆盖。</p>
 */
@Component
@ConfigurationProperties(prefix = "aps.security")
public class ApiSecurityProperties {

    private static final String DEFAULT_WRITE_KEY = "dev-write-key";
    private static final String DEFAULT_READ_KEY = "dev-read-key";
    private static final String DEFAULT_JWT_SECRET = "hengce-aps-dev-secret-please-change-32bytes";
    private static final String DEFAULT_REQUIRED_ROLE = "APS_USER";

    /**
     * 认证模式：{@code prod/dev/off}；为空时按 active profile 推断。
     */
    private String mode;

    /**
     * 开发模式 API Key。
     */
    private ApiKeys apiKeys = new ApiKeys();

    /**
     * JWT 配置。
     */
    private Jwt jwt = new Jwt();

    /**
     * 限流配置。
     */
    private RateLimit rateLimit = new RateLimit();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public ApiKeys getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * API Key 配置。
     */
    public static class ApiKeys {

        /**
         * 写密钥：允许写端点。
         */
        private String write = DEFAULT_WRITE_KEY;

        /**
         * 读密钥：仅读端点，写端点返回 403。
         */
        private String read = DEFAULT_READ_KEY;

        public String getWrite() {
            return write;
        }

        public void setWrite(String write) {
            this.write = write == null || write.isBlank() ? DEFAULT_WRITE_KEY : write;
        }

        public String getRead() {
            return read;
        }

        public void setRead(String read) {
            this.read = read == null || read.isBlank() ? DEFAULT_READ_KEY : read;
        }
    }

    /**
     * JWT 配置（HS256）。
     */
    public static class Jwt {

        /**
         * HS256 共享密钥，至少 32 字节。
         */
        private String secret = DEFAULT_JWT_SECRET;

        /**
         * 必须包含的角色。
         */
        private String requiredRole = DEFAULT_REQUIRED_ROLE;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null || secret.isBlank() ? DEFAULT_JWT_SECRET : secret;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole == null || requiredRole.isBlank()
                    ? DEFAULT_REQUIRED_ROLE : requiredRole;
        }
    }

    /**
     * 限流配置（单实例固定窗口）。
     */
    public static class RateLimit {

        /**
         * 是否启用。
         */
        private boolean enabled = true;

        /**
         * 每个窗口允许的请求数。
         */
        private int capacity = 100;

        /**
         * 窗口长度（秒）。
         */
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
