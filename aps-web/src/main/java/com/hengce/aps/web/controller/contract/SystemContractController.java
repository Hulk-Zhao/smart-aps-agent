package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理域占位契约（system 分组）。
 *
 * <p>健康检查 {@code GET /api/v1/health} 已实现；本类补充认证、用户、审计与配置端点。
 * 字段契约见 {@code docs/api/openapi/system.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "系统管理", description = "认证、用户、审计日志、系统配置（P0 占位契约）")
public class SystemContractController {

    /**
     * 登录。
     *
     * @param body 登录字段
     * @return 占位响应
     */
    @Operation(summary = "登录", description = "状态：占位。请求字段见 LoginRequest；密码不得记入日志。")
    @PostMapping("/auth/login")
    public Result<Void> login(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 用户列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "用户列表", description = "状态：占位。")
    @GetMapping("/users")
    public Result<Void> listUsers(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建用户。
     *
     * @param body 用户字段
     * @return 占位响应
     */
    @Operation(summary = "创建用户", description = "状态：占位。请求字段见 UserCreateRequest。")
    @PostMapping("/users")
    public Result<Void> createUser(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 审计日志查询。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "审计日志查询", description = "状态：占位。")
    @GetMapping("/audit-logs")
    public Result<Void> listAuditLogs(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 读取系统配置。
     *
     * @return 占位响应
     */
    @Operation(summary = "读取系统配置", description = "状态：占位。")
    @GetMapping("/configs")
    public Result<Void> getConfigs() {
        throw ContractSupport.notImplemented();
    }

    /**
     * 更新系统配置。
     *
     * @param body 配置项
     * @return 占位响应
     */
    @Operation(summary = "更新系统配置", description = "状态：占位。")
    @PutMapping("/configs")
    public Result<Void> updateConfigs(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }
}
