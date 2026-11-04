package com.hengce.aps.solver.api;

import java.util.List;
import java.util.Map;

/**
 * 求解度量，对应 {@code solve_task.metrics} JSONB。
 *
 * @param durationMs 求解耗时（毫秒）
 * @param stepCount 求解步数
 * @param unimprovedStepCount 无改进步数
 * @param terminationReason 结束原因：TIME_LIMIT / STEP_LIMIT / UNIMPROVED_LIMIT / TERMINATED
 * @param scoreHistory 评分轨迹
 * @param assignedTaskCount 已分配任务数
 * @param extra 引擎扩展度量
 */
public record SolveMetrics(
        long durationMs,
        long stepCount,
        int unimprovedStepCount,
        String terminationReason,
        List<ScorePoint> scoreHistory,
        int assignedTaskCount,
        Map<String, Object> extra
) {

    /**
     * 归一化评分轨迹。
     */
    public SolveMetrics {
        scoreHistory = List.copyOf(scoreHistory == null ? List.of() : scoreHistory);
    }
}
