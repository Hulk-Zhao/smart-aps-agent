package com.hengce.aps.solver.dsl;

/**
 * 约束 DSL 加载入口，解析实现将在 SE 阶段补充。
 */
public interface ConstraintDslLoader {

    /**
     * 加载 DSL 文本。
     *
     * @param source YAML 或 JSON 文本
     * @return 解析后的 DSL 文档
     */
    ConstraintDslDocument load(String source);
}
