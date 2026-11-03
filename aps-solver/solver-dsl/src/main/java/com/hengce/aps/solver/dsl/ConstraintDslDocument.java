package com.hengce.aps.solver.dsl;

import java.util.List;

/**
 * 约束 DSL 文档。
 *
 * @param version DSL 版本
 * @param constraints 约束列表
 */
public record ConstraintDslDocument(String version, List<ConstraintDefinition> constraints) {
}
