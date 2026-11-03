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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 甘特图与计划调整域占位契约（gantt 分组）。
 *
 * <p>字段契约见 {@code docs/api/openapi/gantt.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "甘特图与计划", description = "甘特图、人工调整、计划发布/回滚（P0 占位契约）")
public class GanttContractController {

    /**
     * 资源甘特图。
     *
     * @param sandboxId 场景 UUID
     * @param from 起始时间
     * @param to 结束时间
     * @return 占位响应
     */
    @Operation(summary = "资源甘特图", description = "状态：占位。from/to 为 ISO-8601 UTC。")
    @GetMapping("/gantt/resource")
    public Result<Void> resourceGantt(@RequestParam String sandboxId,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 订单甘特图。
     *
     * @param sandboxId 场景 UUID
     * @param from 起始时间
     * @param to 结束时间
     * @return 占位响应
     */
    @Operation(summary = "订单甘特图", description = "状态：占位。from/to 为 ISO-8601 UTC。")
    @GetMapping("/gantt/order")
    public Result<Void> orderGantt(@RequestParam String sandboxId,
                                   @RequestParam String from,
                                   @RequestParam String to) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 人工调整排产任务。
     *
     * @param id 排产任务 ID
     * @param body 调整内容
     * @return 占位响应
     */
    @Operation(summary = "人工调整任务", description = "状态：占位。请求字段见 TaskAdjustRequest。")
    @PostMapping("/tasks/{id}/adjust")
    public Result<Void> adjustTask(@PathVariable String id,
                                   @RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 发布计划。
     *
     * @param id 计划/场景 UUID
     * @return 占位响应
     */
    @Operation(summary = "发布计划", description = "状态：占位。")
    @PostMapping("/plans/{id}/publish")
    public Result<Void> publishPlan(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 回滚计划。
     *
     * @param id 计划/场景 UUID
     * @return 占位响应
     */
    @Operation(summary = "回滚计划", description = "状态：占位。")
    @PostMapping("/plans/{id}/rollback")
    public Result<Void> rollbackPlan(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }
}
