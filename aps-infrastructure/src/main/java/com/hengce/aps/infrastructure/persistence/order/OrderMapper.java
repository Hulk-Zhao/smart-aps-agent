package com.hengce.aps.infrastructure.persistence.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单主表 Mapper。
 *
 * <p>租户过滤由 BD-01 的 TenantLineInnerInterceptor 统一追加，逻辑删除由 {@code @TableLogic} 处理。
 * 自定义只读 SQL 仅用于客户存在性校验；工艺路线与 BOM 校验分别复用 BD-07 / BD-03 的查询能力。</p>
 */
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 统计未删除的客户数量，供订单 customerId 引用校验。
     *
     * @param customerId 客户主键
     * @return 匹配数量
     */
    @Select("SELECT COUNT(1) FROM customer WHERE id = #{customerId} AND deleted = false")
    long countCustomer(@Param("customerId") UUID customerId);
}
