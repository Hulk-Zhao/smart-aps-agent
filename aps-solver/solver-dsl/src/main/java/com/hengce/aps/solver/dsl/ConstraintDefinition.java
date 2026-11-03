package com.hengce.aps.solver.dsl;

/**
 * 单条约束定义。
 *
 * @param code 约束编码
 * @param rule 约束表达式
 * @param hard 是否为硬约束
 */
public record ConstraintDefinition(String code, String rule, boolean hard) {
}
