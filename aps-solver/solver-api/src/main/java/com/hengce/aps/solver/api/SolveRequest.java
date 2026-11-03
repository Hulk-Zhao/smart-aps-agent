package com.hengce.aps.solver.api;

import java.util.List;
import java.util.Map;

/**
 * 求解请求。
 *
 * @param taskNo 求解任务号
 * @param engine 引擎编码，为空时使用默认引擎
 * @param orderNos 参与排产的订单号
 * @param parameters 引擎参数
 */
public record SolveRequest(
        String taskNo,
        String engine,
        List<String> orderNos,
        Map<String, Object> parameters) {
}
