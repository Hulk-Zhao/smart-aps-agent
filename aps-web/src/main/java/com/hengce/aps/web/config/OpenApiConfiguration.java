package com.hengce.aps.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 分组配置。
 *
 * <p>按 PRD 9.1 的域拆分为 materials / orders / solve / gantt / system 五组，
 * 访问 {@code /v3/api-docs/{group}} 获取对应文档；静态契约与分组说明见 {@code docs/api/openapi/}。</p>
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * 平台级 OpenAPI 元信息与安全方案。
     *
     * @return OpenAPI 定义
     */
    @Bean
    public OpenAPI apsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("衡策 APS 开源版 API")
                        .version("v0.1.0")
                        .description("""
                                MVP 契约骨架。统一返回体见 Result；分页、幂等、错误码与时间格式约定见 docs/api/conventions.md。
                                标注“占位”的端点尚未实现，调用返回 501 + code=50100。
                                """)
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("apiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")));
    }

    /**
     * 物料与主数据域。
     *
     * @return 分组文档
     */
    @Bean
    public GroupedOpenApi materialsOpenApi() {
        return GroupedOpenApi.builder()
                .group("materials")
                .pathsToMatch("/api/v1/materials/**", "/api/v1/boms/**", "/api/v1/resources/**",
                        "/api/v1/calendars/**", "/api/v1/routings/**", "/api/v1/mrp/**")
                .build();
    }

    /**
     * 订单与场景域。
     *
     * @return 分组文档
     */
    @Bean
    public GroupedOpenApi ordersOpenApi() {
        return GroupedOpenApi.builder()
                .group("orders")
                .pathsToMatch("/api/v1/orders/**", "/api/v1/strategies/**", "/api/v1/sandboxes/**")
                .build();
    }

    /**
     * 求解与 Agent 域。
     *
     * @return 分组文档
     */
    @Bean
    public GroupedOpenApi solveOpenApi() {
        return GroupedOpenApi.builder()
                .group("solve")
                .pathsToMatch("/api/v1/solve-tasks/**", "/api/v1/agent/**")
                .build();
    }

    /**
     * 甘特图与计划调整域。
     *
     * @return 分组文档
     */
    @Bean
    public GroupedOpenApi ganttOpenApi() {
        return GroupedOpenApi.builder()
                .group("gantt")
                .pathsToMatch("/api/v1/gantt/**", "/api/v1/tasks/**", "/api/v1/plans/**")
                .build();
    }

    /**
     * 系统管理域。
     *
     * @return 分组文档
     */
    @Bean
    public GroupedOpenApi systemOpenApi() {
        return GroupedOpenApi.builder()
                .group("system")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/users/**", "/api/v1/audit-logs/**",
                        "/api/v1/configs/**", "/api/v1/health")
                .build();
    }
}
