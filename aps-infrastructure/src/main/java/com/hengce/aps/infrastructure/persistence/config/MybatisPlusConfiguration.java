package com.hengce.aps.infrastructure.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.hengce.aps.common.constant.ApsConstants;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 持久层基座配置。
 *
 * <p>拦截器顺序遵循官方约定：多租户 → 分页 → 乐观锁。顺序错误会导致 1) 分页 SQL 未带租户条件、
 * 2) 乐观锁 version 条件被后续解析覆盖。</p>
 *
 * <p>Mapper 扫描根包固定为 {@code com.hengce.aps.infrastructure.persistence.mapper}，
 * 各业务流的 Mapper 必须放在该包及其子包下，避免扫描到 web/service 层。</p>
 */
@Configuration
@MapperScan(basePackages = MybatisPlusConfiguration.MAPPER_PACKAGE)
public class MybatisPlusConfiguration {

    /**
     * Mapper 接口扫描根包。
     */
    public static final String MAPPER_PACKAGE = "com.hengce.aps.infrastructure.persistence.mapper";

    /**
     * 构建 MyBatis-Plus 拦截器链。
     *
     * @return 含租户、分页、乐观锁能力的拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new ApsTenantLineHandler()));
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit((long) ApsConstants.MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 注册租户与审计字段自动填充处理器。
     *
     * @return MetaObjectHandler
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new ApsMetaObjectHandler();
    }
}
