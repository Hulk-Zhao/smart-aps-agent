package com.hengce.aps.web.controller;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.domain.model.ProductionOrder;
import com.hengce.aps.service.order.ProductionOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生产订单查询 API。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/orders")
@Tag(name = "订单与场景", description = "生产订单查询")
public class OrderController {

    private final ProductionOrderService productionOrderService;

    /**
     * 构造控制器。
     *
     * @param productionOrderService 订单服务
     */
    public OrderController(ProductionOrderService productionOrderService) {
        this.productionOrderService = productionOrderService;
    }

    /**
     * 订单列表（统一分页出参）。
     *
     * @param query 分页参数
     * @return 分页订单
     */
    @Operation(summary = "订单列表", description = "已实现。统一分页出参 PageResult。")
    @GetMapping
    public Result<PageResult<ProductionOrder>> list(@ParameterObject PageQuery query) {
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        List<ProductionOrder> all = productionOrderService.listOrders();
        int from = Math.min(normalized.offset(), all.size());
        int to = Math.min(from + normalized.getSize(), all.size());
        return Result.success(PageResult.of(all.subList(from, to), normalized, all.size()));
    }

    /**
     * 订单详情。
     *
     * @param orderNo 订单号
     * @return 订单
     */
    @Operation(summary = "订单详情", description = "已实现。路径参数为 order_no。")
    @GetMapping("/{orderNo}")
    public Result<ProductionOrder> detail(@PathVariable String orderNo) {
        return Result.success(productionOrderService.getOrder(orderNo));
    }
}
