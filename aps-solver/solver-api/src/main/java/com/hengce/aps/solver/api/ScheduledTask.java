package com.hengce.aps.solver.api;

import java.time.LocalDateTime;

/**
 * 单个排产结果：订单在资源上的时间占用。
 *
 * @param orderNo 订单号
 * @param resourceCode 资源编码
 * @param startTime 开始时间
 * @param endTime 结束时间
 */
public record ScheduledTask(
        String orderNo,
        String resourceCode,
        LocalDateTime startTime,
        LocalDateTime endTime) {
}
