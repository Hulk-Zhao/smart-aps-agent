package com.hengce.aps.service.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计快照脱敏工具（C31 / 25.2.2）。
 *
 * <p>敏感键（不区分大小写）在写入前替换为 {@code [REDACTED]}；嵌套 Map/List 递归处理。
 * 返回新对象，不修改调用方传入的 Map。</p>
 */
public final class AuditValues {

    /**
     * 需要脱敏的键。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordhash", "token", "accesstoken", "secret", "apikey");

    /**
     * 脱敏占位符。
     */
    public static final String REDACTED = "[REDACTED]";

    private AuditValues() {
    }

    /**
     * 递归脱敏并返回副本。
     *
     * @param value 原始快照，可为 null
     * @return 脱敏后的副本；入参为 null 时返回 null
     */
    public static Map<String, Object> redact(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (isSensitive(key)) {
                result.put(key, REDACTED);
            } else {
                result.put(key, redactValue(item));
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                nested.put(textKey, isSensitive(textKey) ? REDACTED : redactValue(item));
            });
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>();
            for (Object item : list) {
                nested.add(redactValue(item));
            }
            return nested;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }
}
