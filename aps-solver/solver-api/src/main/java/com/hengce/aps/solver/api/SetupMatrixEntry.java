package com.hengce.aps.solver.api;

import java.util.UUID;

/**
 * 换型矩阵条目（contract-freeze C12 §17.3），M1 为可选降级能力。
 *
 * <p>仅当同一资源上相邻两个任务命中该条目时，覆盖后一个任务的静态 {@code setupSeconds}；
 * 未命中回退 {@code SchedulingOperation.setupSeconds}。</p>
 *
 * @param resourceId 适用资源，可空；空表示适用于所有资源
 * @param keyType 键类型：OPERATION_CODE / MATERIAL_CODE
 * @param fromKey 前序任务键，可空；空表示通配
 * @param toKey 当前任务键，可空；空表示通配
 * @param setupSeconds 换型时长（秒），M1 约定整秒且非负
 */
public record SetupMatrixEntry(
        UUID resourceId,
        String keyType,
        String fromKey,
        String toKey,
        int setupSeconds
) {
}
