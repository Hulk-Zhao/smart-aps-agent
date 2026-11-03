package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求解与 Agent 域占位契约（solve 分组）。
 *
 * <p>POST /solve-tasks 已由 {@code SolveTaskController} 实现；本类补充任务详情/取消与 Agent 端点。
 * WebSocket 进度推送不在 OpenAPI 中建模，契约见 {@code docs/api/openapi/solve.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "求解与 Agent", description = "求解任务、Agent（P0 占位契约）")
public class SolveContractController {

    /**
     * 求解任务详情。
     *
     * @param id 求解任务 ID
     * @return 占位响应
     */
    @Operation(summary = "求解任务详情", description = "状态：占位。")
    @GetMapping("/solve-tasks/{id}")
    public Result<Void> getSolveTask(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 取消求解任务。
     *
     * @param id 求解任务 ID
     * @return 占位响应
     */
    @Operation(summary = "取消求解任务", description = "状态：占位。")
    @PostMapping("/solve-tasks/{id}/cancel")
    public Result<Void> cancelSolveTask(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 上报异常事件。
     *
     * @param body 事件字段
     * @return 占位响应
     */
    @Operation(summary = "上报异常事件", description = "状态：占位。")
    @PostMapping("/agent/events")
    public Result<Void> reportEvent(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 自然语言转 DSL。
     *
     * @param body 转换请求
     * @return 占位响应
     */
    @Operation(summary = "自然语言转 DSL", description = "状态：占位。")
    @PostMapping("/agent/nl-to-dsl")
    public Result<Void> nlToDsl(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 归因解释。
     *
     * @param taskId 排产任务 ID
     * @return 占位响应
     */
    @Operation(summary = "归因解释", description = "状态：占位。")
    @GetMapping("/agent/explain/{taskId}")
    public Result<Void> explain(@PathVariable String taskId) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 对话式 what-if。
     *
     * @param body 对话参数
     * @return 占位响应
     */
    @Operation(summary = "对话式 what-if", description = "状态：占位。")
    @PostMapping("/agent/whatif")
    public Result<Void> whatIf(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }
}
