package com.hengce.aps.solver.api;

import java.util.List;

/**
 * 约束归因，用于 Agent 解释与甘特提示；{@code taskIds} 指向 {@link TaskAssignment#taskId()}。
 *
 * @param constraintCode 约束编码
 * @param type 约束类型：HARD / SOFT
 * @param taskIds 受影响任务的 {@code taskId}
 * @param description 面向人的说明
 */
public record ConstraintJustification(
        String constraintCode,
        String type,
        List<Long> taskIds,
        String description
) {

    /**
     * 归一化任务标识列表。
     */
    public ConstraintJustification {
        taskIds = List.copyOf(taskIds == null ? List.of() : taskIds);
    }
}
