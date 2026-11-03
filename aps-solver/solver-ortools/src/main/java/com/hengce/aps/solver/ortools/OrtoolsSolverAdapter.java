package com.hengce.aps.solver.ortools;

import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolveResult;
import com.hengce.aps.solver.api.SolveStatus;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;

import java.util.List;

/**
 * OR-Tools CP-SAT 适配器骨架，MVP 阶段仅保留占位实现。
 */
public class OrtoolsSolverAdapter implements SolverAdapter {

    @Override
    public SolverEngine engine() {
        return SolverEngine.ORTOOLS;
    }

    @Override
    public SolveResult solve(SolveRequest request) {
        return new SolveResult(
                request.taskNo(),
                SolveStatus.UNSUPPORTED,
                List.of(),
                "OR-Tools 求解逻辑为 v0.2 预留能力");
    }
}
