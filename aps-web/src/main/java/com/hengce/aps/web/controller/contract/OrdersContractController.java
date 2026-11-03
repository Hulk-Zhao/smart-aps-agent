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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单与场景域占位契约（orders 分组）。
 *
 * <p>GET /orders 与 GET /orders/{orderNo} 已由 {@code OrderController} 实现；
 * 本类只补充尚未实现的写操作与策略/场景端点。字段契约见 {@code docs/api/openapi/orders.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "订单与场景", description = "订单、策略、沙箱（P0 占位契约）")
public class OrdersContractController {

    /**
     * 创建订单。
     *
     * @param body 订单字段
     * @return 占位响应
     */
    @Operation(summary = "创建订单", description = "状态：占位。请求字段见 OrderCreateRequest。")
    @PostMapping("/orders")
    public Result<Void> createOrder(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 确认订单。
     *
     * @param id 订单业务编码 order_no
     * @return 占位响应
     */
    @Operation(summary = "确认订单", description = "状态：占位。路径 id 为 order_no。")
    @PutMapping("/orders/{id}/confirm")
    public Result<Void> confirmOrder(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 冻结/解冻订单。
     *
     * @param id 订单业务编码 order_no
     * @param body 冻结参数，freezeStatus=UNFROZEN/FROZEN/LOCKED_TIME_WINDOW/LOCKED_RESOURCE
     * @return 占位响应
     */
    @Operation(summary = "冻结/解冻订单", description = "状态：占位。请求字段见 OrderFreezeRequest。")
    @PutMapping("/orders/{id}/freeze")
    public Result<Void> freezeOrder(@PathVariable String id,
                                    @RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

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
     * 策略列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "策略列表", description = "状态：占位。")
    @GetMapping("/strategies")
    public Result<Void> listStrategies(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建策略。
     *
     * @param body 策略字段
     * @return 占位响应
     */
    @Operation(summary = "创建策略", description = "状态：占位。请求字段见 StrategyCreateRequest。")
    @PostMapping("/strategies")
    public Result<Void> createStrategy(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 克隆策略。
     *
     * @param id 策略主键 UUID
     * @return 占位响应
     */
    @Operation(summary = "克隆策略", description = "状态：占位。")
    @PostMapping("/strategies/{id}/clone")
    public Result<Void> cloneStrategy(@PathVariable String id) {
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
    public Result<Void> applySandbox(@PathVariable String id) {
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
    public Result<Void> compareSandbox(@PathVariable String id,
                                       @RequestParam String targetId) {
        throw ContractSupport.notImplemented();
    }
}
