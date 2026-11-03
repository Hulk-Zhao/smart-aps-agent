package com.hengce.aps.web.controller;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolveResult;
import com.hengce.aps.service.schedule.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求解任务 API。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/solve-tasks")
@Tag(name = "求解与 Agent", description = "同步求解提交")
public class SolveTaskController {

    private final ScheduleService scheduleService;

    public SolveTaskController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * 提交求解任务。
     *
     * @param request 求解请求
     * @return 求解结果
     */
    @Operation(summary = "提交求解任务", description = "已实现（同步 MVP 求解）。")
    @PostMapping
    public Result<SolveResult> submit(@RequestBody SolveRequest request) {
        return Result.success(scheduleService.submit(request));
    }
}
