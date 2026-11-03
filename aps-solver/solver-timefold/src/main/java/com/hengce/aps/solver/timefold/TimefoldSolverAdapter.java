package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolveResult;
import com.hengce.aps.solver.api.SolveStatus;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;

import java.util.List;

/**
 * Timefold Solver 适配器骨架，具体约束与求解逻辑在 SE 阶段实现。
 */
public class TimefoldSolverAdapter implements SolverAdapter {

    @Override
    public SolverEngine engine() {
        return SolverEngine.TIMEFOLD;
    }

    @Override
    public SolveResult solve(SolveRequest request) {
        return new SolveResult(
                request.taskNo(),
                SolveStatus.UNSUPPORTED,
                List.of(),
                "Timefold 求解逻辑尚未实现");
    }
}
