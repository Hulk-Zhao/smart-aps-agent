package com.hengce.aps.service.schedule;

import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolveResult;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 排产服务：按引擎编码路由到具体 SolverAdapter。
 */
@Service
public class ScheduleService {

    private final Map<SolverEngine, SolverAdapter> adapters;

    public ScheduleService(List<SolverAdapter> solverAdapters) {
        Map<SolverEngine, SolverAdapter> registry = new EnumMap<>(SolverEngine.class);
        for (SolverAdapter adapter : solverAdapters) {
            registry.put(adapter.engine(), adapter);
        }
        this.adapters = registry;
    }

    /**
     * 提交求解任务。
     *
     * @param request 求解请求
     * @return 求解结果
     */
    public SolveResult submit(SolveRequest request) {
        SolverEngine engine;
        try {
            engine = SolverEngine.fromCode(request.engine());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
        SolverAdapter adapter = adapters.get(engine);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.SOLVER_NOT_AVAILABLE, "求解引擎未装配: " + engine.getCode());
        }
        return adapter.solve(request);
    }
}
