package com.hengce.aps.service.mrp;

/**
 * 库存批量 upsert 结果。
 *
 * @param created 新增记录数
 * @param updated 更新记录数
 * @param total 本次提交的物料+仓库组合总数
 */
public record InventoryUpsertResult(int created, int updated, int total) {
}
