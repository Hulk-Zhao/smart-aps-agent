package com.hengce.aps.service.mrp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MRP 齐套分析结果（C25）。
 *
 * <p>顶层给出整体分级与按物料聚合的缺料明细；{@code orders} 给出按订单拆分的齐套状态，
 * 便于前端直接渲染“哪张订单缺什么料”。{@code availableQty = onHand + transit - safetyStock}，
 * 可为负（表示库存低于安全库存）。</p>
 *
 * @param sandboxId 排产场景标识（原样回显）
 * @param analyzedAt 分析时间（UTC）
 * @param status 齐套分级 FULL / PARTIAL / SHORTAGE
 * @param blocking 请求是否要求按缺料阻塞排产
 * @param blocked 是否实际阻塞（blocking=true 且存在缺料）
 * @param inventoryEnabled 库存表读取开关是否开启
 * @param orderCount 参与分析的订单数
 * @param warnings 展开过程中的非致命提示（无 ACTIVE BOM、环引用等）
 * @param orders 按订单拆分的齐套结果
 * @param shortages 按物料聚合的缺料明细
 */
public record MrpAnalysisResponse(UUID sandboxId, OffsetDateTime analyzedAt, MrpStatus status,
                                  boolean blocking, boolean blocked, boolean inventoryEnabled,
                                  int orderCount, List<String> warnings, List<MrpOrderResult> orders,
                                  List<MrpShortage> shortages) {

    /**
     * 单张订单的齐套结果。
     *
     * @param orderNo 订单号
     * @param productCode 产品物料编码
     * @param qty 订单数量
     * @param dueDate 交期
     * @param kitted 是否齐套
     * @param shortages 该订单的缺料明细
     */
    public record MrpOrderResult(String orderNo, String productCode, BigDecimal qty, LocalDate dueDate,
                                 boolean kitted, List<MrpOrderShortage> shortages) {
    }

    /**
     * 订单级缺料明细。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param requiredQty 该订单需求数量（已含 BOM 损耗）
     * @param allocatedQty 分配到该订单的可用数量
     * @param shortageQty 短缺数量
     */
    public record MrpOrderShortage(String materialCode, String materialName, BigDecimal requiredQty,
                                   BigDecimal allocatedQty, BigDecimal shortageQty) {
    }

    /**
     * 按物料聚合的缺料明细。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param requiredQty 跨订单毛需求合计
     * @param availableQty 可用量 = onHand + transit - safetyStock
     * @param shortageQty 短缺数量
     * @param earliestRequiredAt 最早需求时间（最早订单交期当日 00:00 UTC）
     * @param affectedOrderNos 受影响订单号
     * @param suggestedPurchaseDate 建议下单日期（includeSuggestions=true 时输出）
     * @param suggestedQty 建议采购数量（includeSuggestions=true 时输出）
     * @param supplierCode 建议供应商编码；M2 未接供应商主数据，固定为 null
     */
    public record MrpShortage(String materialCode, String materialName, BigDecimal requiredQty,
                              BigDecimal availableQty, BigDecimal shortageQty,
                              OffsetDateTime earliestRequiredAt, List<String> affectedOrderNos,
                              OffsetDateTime suggestedPurchaseDate, BigDecimal suggestedQty,
                              String supplierCode) {
    }
}
