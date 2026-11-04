package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单与场景域占位契约（orders 分组）。
 *
 * <p>GET /orders、GET /orders/{orderNo}、POST /orders、PUT /orders/{orderNo}、
 * PUT /orders/{orderNo}/confirm、PUT /orders/{orderNo}/freeze 已由 {@code OrderController}
 * 实现（M1-SE-05）；`/strategies/**` 已由 M2 的 {@code StrategyController} 实现（C19）；
 * `/sandboxes` 全部端点已由 M3-W5 的 {@code SandboxController} 实现（C32）。上述占位方法均已退役，
 * 避免 Ambiguous mapping。本类只保留尚未实现的 ATP 端点。字段契约见
 * {@code docs/api/openapi/orders.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "订单与场景", description = "ATP（P0 占位契约）")
public class OrdersContractController {

    /**
     * ATP 交期预估。
     *
     * @param body 预估参数
     * @return 占位响应
     */
    @Operation(summary = "ATP 交期预估", description = "状态：占位。请求字段见 AtpRequest。")
    @RequiresPermission("OM_ATP")
    @PostMapping("/orders/atp")
    public Result<Void> atp(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }
}
