package com.hengce.aps.solver.timefold.setup;

import com.hengce.aps.solver.api.SetupMatrixEntry;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 换型矩阵解析（contract-freeze C12 §17.3.2 匹配优先级）。
 *
 * <p>仅当同一资源上相邻两个任务命中条目时覆盖静态换型工时；优先级为
 * 指定 {@code resourceId} 高于空（通配），{@code fromKey}/{@code toKey} 精确高于空，
 * 同优先级取较大 {@code setupSeconds}。</p>
 */
public final class SetupMatrixResolver {

    /**
     * 按工序编码匹配。
     */
    public static final String KEY_OPERATION_CODE = "OPERATION_CODE";

    /**
     * 按物料编码匹配。
     */
    public static final String KEY_MATERIAL_CODE = "MATERIAL_CODE";

    private SetupMatrixResolver() {
    }

    /**
     * 解析相邻任务的换型秒数。
     *
     * @param matrix 换型矩阵
     * @param resourceId 当前任务资源
     * @param operationCode 当前任务工序编码
     * @param materialCode 当前任务物料编码
     * @param previousOperationCode 前序任务工序编码
     * @param previousMaterialCode 前序任务物料编码
     * @return 命中的换型秒数；未命中返回 {@code null}，由调用方回退静态值
     */
    public static Long resolve(
            List<SetupMatrixEntry> matrix,
            UUID resourceId,
            String operationCode,
            String materialCode,
            String previousOperationCode,
            String previousMaterialCode) {
        if (matrix == null || matrix.isEmpty()) {
            return null;
        }
        int bestRank = -1;
        Long bestSetup = null;
        for (SetupMatrixEntry entry : matrix) {
            if (entry.setupSeconds() < 0) {
                throw new IllegalArgumentException("换型矩阵的 setupSeconds 不能为负数: " + entry);
            }
            if (entry.resourceId() != null && !entry.resourceId().equals(resourceId)) {
                continue;
            }
            String keyType = entry.keyType() == null ? "" : entry.keyType().trim().toUpperCase(Locale.ROOT);
            String fromKey;
            String toKey;
            if (KEY_OPERATION_CODE.equals(keyType)) {
                fromKey = previousOperationCode;
                toKey = operationCode;
            } else if (KEY_MATERIAL_CODE.equals(keyType)) {
                fromKey = previousMaterialCode;
                toKey = materialCode;
            } else {
                continue;
            }
            if (!matches(entry.fromKey(), fromKey) || !matches(entry.toKey(), toKey)) {
                continue;
            }
            int rank = (entry.resourceId() != null ? 4 : 0)
                    + (entry.fromKey() != null ? 2 : 0)
                    + (entry.toKey() != null ? 1 : 0);
            if (rank > bestRank || (rank == bestRank && bestSetup != null
                    && entry.setupSeconds() > bestSetup)) {
                bestRank = rank;
                bestSetup = (long) entry.setupSeconds();
            }
        }
        return bestSetup;
    }

    private static boolean matches(String key, String actual) {
        return key == null || key.equals(actual);
    }
}
