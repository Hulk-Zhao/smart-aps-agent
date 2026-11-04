package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 订单与场景域占位契约（orders 分组）。
 *
 * <p>GET /orders、GET /orders/{orderNo}、POST /orders、PUT /orders/{orderNo}、
 * PUT /orders/{orderNo}/confirm、PUT /orders/{orderNo}/freeze 已由 {@code OrderController}
 * 实现（M1-SE-05）；`/strategies/**` 已由 M2 的 {@code StrategyController} 实现（C19），
 * 对应占位方法已退役，避免 Ambiguous mapping；本类只补充尚未实现的 ATP 与场景端点。
 * 字段契约见 {@code docs/api/openapi/orders.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "订单与场景", description = "策略、沙箱（P0 占位契约）")
public class OrdersContractController {

    /**
     * ATP 交期预估。
     *
     * @param body 预估参数
     * @return 占位响应
     */
    @Operation(summary = "ATP 交期预估", description = "状态：占位。请求字段见 AtpRequest。")
    @PostMapping("/orders/atp")
    public Result<Void> atp(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 场景列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "沙箱列表", description = "状态：占位。")
    @GetMapping("/sandboxes")
    public Result<Void> listSandboxes(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建场景。
     *
     * @param body 场景字段
     * @return 占位响应
     */
    @Operation(summary = "创建沙箱", description = "状态：占位。请求字段见 SandboxCreateRequest。")
    @PostMapping("/sandboxes")
    public Result<Void> createSandbox(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 应用场景为主计划。
     *
     * @param id 场景主键 UUID
     * @return 占位响应
     */
    @Operation(summary = "应用沙箱", description = "状态：占位。")
    @PostMapping("/sandboxes/{id}/apply")
    public Result<Void> applySandbox(@PathVariable UUID id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 场景对比。
     *
     * @param id 场景主键 UUID
     * @param targetId 对比目标场景 UUID
     * @return 占位响应
     */
    @Operation(summary = "沙箱对比", description = "状态：占位。")
    @GetMapping("/sandboxes/{id}/compare")
    public Result<Void> compareSandbox(@PathVariable UUID id,
                                       @RequestParam UUID targetId) {
        throw ContractSupport.notImplemented();
    }
}
