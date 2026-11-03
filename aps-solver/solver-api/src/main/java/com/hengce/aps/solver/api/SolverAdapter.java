package com.hengce.aps.solver.api;

/**
 * 求解器统一抽象。业务层只依赖此接口，具体引擎实现位于 solver-timefold / solver-ortools。
 */
public interface SolverAdapter {

    /**
     * 引擎类型。
     *
     * @return 引擎类型
     */
    SolverEngine engine();

    /**
     * 执行求解。
     *
     * @param request 求解请求
     * @return 求解结果
     */
    SolveResult solve(SolveRequest request);
}
