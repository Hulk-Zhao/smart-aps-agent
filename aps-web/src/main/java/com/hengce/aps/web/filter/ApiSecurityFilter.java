package com.hengce.aps.web.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.web.config.ApiSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M2 认证过滤器（C27）。
 *
 * <p>按 profile 分级：{@code prod} 全端点强制 JWT（HS256）；{@code dev} 仅写端点强制，
 * 支持 {@code X-API-Key} 直通（写密钥放行、读密钥 403）；{@code off} 全放行。
 * <strong>默认（未配置 {@code aps.security.mode} 且未启用 prod profile）为 {@code off}</strong>，
 * 避免本地 demo/回归脚本（不带任何凭证）被挡在门外；需要认证时显式设置
 * {@code --aps.security.mode=dev} 或环境变量 {@code APS_SECURITY_MODE=dev}。
 * 认证失败直接输出 {@link Result}，不进入 MVC 异常处理。</p>
 *
 * <p><strong>审计身份注入</strong>：认证通过后把操作人写入 {@link AuditContext}（供
 * {@code ApsMetaObjectHandler} 填充 created_by/updated_by），请求结束（finally）清理 ThreadLocal。
 * dev 模式取 {@code X-User-Id}（非法 UUID 返回 400），缺省用 {@code aps.audit.dev-user-id}；
 * prod 模式预留 JWT subject（M3 令牌签发后接入）；off 模式<strong>不注入</strong>，保持 NULL，不伪造账号。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiSecurityFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityFilter.class);

    private static final String API_PREFIX = ApsConstants.API_PREFIX;

    private static final String HEALTH_PATH = API_PREFIX + "/health";

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String USER_ID_HEADER = ApsConstants.HEADER_USER_ID;

    private static final String DEV_USER_ID_PROPERTY = "aps.audit.dev-user-id";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String JWT_ALGORITHM = "HS256";

    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE =
            new TypeReference<>() {
            };

    private final ApiSecurityProperties properties;

    private final ObjectMapper objectMapper;

    private final org.springframework.core.env.Environment environment;

    public ApiSecurityFilter(ApiSecurityProperties properties, ObjectMapper objectMapper,
                             org.springframework.core.env.Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX) || path.equals(HEALTH_PATH) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String mode = resolveMode();
        AuditContext.clear();
        try {
            if ("off".equals(mode)) {
                // off：不注入操作人，审计列保持 NULL，不伪造账号
                filterChain.doFilter(request, response);
                return;
            }
            boolean mutating = WRITE_METHODS.contains(request.getMethod());
            if ("dev".equals(mode) && !mutating) {
                filterChain.doFilter(request, response);
                return;
            }

            if ("prod".equals(mode)) {
                if (!authenticateJwt(request, response)) {
                    return;
                }
                // 扩展点：M3 令牌签发后取 payload.sub 写入 AuditContext（当前留空，不伪造）
            } else {
                if (!authenticateDev(request, response)) {
                    return;
                }
                if (!applyDevAuditUser(request, response)) {
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    /**
     * dev 模式解析操作人：优先 {@code X-User-Id}（非法 UUID → 400），否则用 {@code aps.audit.dev-user-id}。
     *
     * @param request 请求
     * @param response 响应
     * @return 是否继续处理
     * @throws IOException 写错误响应失败
     */
    private boolean applyDevAuditUser(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String header = request.getHeader(USER_ID_HEADER);
        if (header != null && !header.isBlank()) {
            UUID userId = parseUuid(header);
            if (userId == null) {
                writeError(response, ErrorCode.BAD_REQUEST, "X-User-Id 必须为合法 UUID");
                return false;
            }
            AuditContext.setCurrentUserId(userId);
            return true;
        }
        String configured = environment.getProperty(DEV_USER_ID_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            UUID userId = parseUuid(configured);
            if (userId == null) {
                LOGGER.warn("aps.audit.dev-user-id 不是合法 UUID，已忽略: {}", configured);
            } else {
                AuditContext.setCurrentUserId(userId);
            }
        }
        return true;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String resolveMode() {
        String configured = properties.getMode();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().toLowerCase();
        }
        String[] profiles = environment.getActiveProfiles();
        for (String profile : profiles) {
            if ("prod".equalsIgnoreCase(profile)) {
                return "prod";
            }
        }
        // 未显式配置且非 prod：默认关闭认证，保证本地 demo / 回归脚本零改造可用（C27）
        return "off";
    }

    private boolean authenticateDev(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            if (apiKey.equals(properties.getApiKeys().getWrite())) {
                return true;
            }
            if (apiKey.equals(properties.getApiKeys().getRead())) {
                writeError(response, ErrorCode.FORBIDDEN, "读密钥不能访问写端点");
                return false;
            }
            writeError(response, ErrorCode.UNAUTHORIZED, null);
            return false;
        }
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authenticateJwt(request, response);
        }
        writeError(response, ErrorCode.UNAUTHORIZED, null);
        return false;
    }

    private boolean authenticateJwt(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, ErrorCode.UNAUTHORIZED, null);
            return false;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        VerifyResult result = verifyJwt(token);
        if (result == VerifyResult.FORBIDDEN) {
            writeError(response, ErrorCode.FORBIDDEN, "令牌缺少必需角色");
            return false;
        }
        if (result != VerifyResult.OK) {
            writeError(response, ErrorCode.UNAUTHORIZED, null);
            return false;
        }
        return true;
    }

    private VerifyResult verifyJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return VerifyResult.INVALID;
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Map<String, Object> header = readJson(decoder.decode(parts[0]));
            if (header == null || !JWT_ALGORITHM.equals(header.get("alg"))) {
                return VerifyResult.INVALID;
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] actual = decoder.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return VerifyResult.INVALID;
            }
            Map<String, Object> payload = readJson(decoder.decode(parts[1]));
            if (payload == null) {
                return VerifyResult.INVALID;
            }
            Object exp = payload.get("exp");
            if (exp instanceof Number number && number.longValue() < System.currentTimeMillis() / 1000L) {
                return VerifyResult.INVALID;
            }
            if (!hasRequiredRole(payload)) {
                return VerifyResult.FORBIDDEN;
            }
            return VerifyResult.OK;
        } catch (RuntimeException | java.security.GeneralSecurityException exception) {
            LOGGER.debug("JWT 校验失败: {}", exception.getMessage());
            return VerifyResult.INVALID;
        }
    }

    private Map<String, Object> readJson(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, JSON_MAP_TYPE);
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean hasRequiredRole(Map<String, Object> payload) {
        String required = properties.getJwt().getRequiredRole();
        Object roles = payload.get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(String::valueOf).anyMatch(required::equals);
        }
        Object role = payload.get("role");
        return role != null && required.equals(String.valueOf(role));
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        Result<Void> body = message == null
                ? Result.failure(errorCode)
                : Result.failure(errorCode, message);
        response.setStatus(errorCode == ErrorCode.FORBIDDEN
                ? HttpServletResponse.SC_FORBIDDEN
                : errorCode == ErrorCode.BAD_REQUEST
                ? HttpServletResponse.SC_BAD_REQUEST : HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * JWT 校验结果。
     */
    private enum VerifyResult {
        /** 通过。 */
        OK,
        /** 凭证无效。 */
        INVALID,
        /** 凭证有效但权限不足。 */
        FORBIDDEN
    }
}
