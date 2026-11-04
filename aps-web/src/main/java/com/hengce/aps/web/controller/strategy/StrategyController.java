package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.strategy.StrategyExportPayload;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 策略接口（C19）：CRUD、克隆、导入导出；8 条软约束权重合计必须为 100。
 *
 * <p>列表与详情均返回 {@code rules} 与 {@code ruleCount}/{@code weightSum}；
 * PUT 为全量替换 rules，版本冲突返回 40901，权重和不为 100 返回 42200。</p>
 */
@RestController
@RequestMapping("/api/v1/strategies")
@Tag(name = "策略", description = "排产策略 CRUD、克隆、导入导出（M2-SE-01）")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /**
     * 策略列表。
     *
     * @param query 分页与排序参数
     * @param name 名称关键字（模糊）
     * @param direction 排产方向（精确）
     * @return 分页结果，每项携带完整 rules 与 ruleCount/weightSum
     */
    @Operation(summary = "策略列表", description = "每项返回 rules 与 ruleCount/weightSum，供前端展示规则数与权重和。")
    @GetMapping
    public Result<PageResult<StrategyView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "策略名称关键字") @RequestParam(required = false) String name,
            @Parameter(description = "FORWARD/BACKWARD/BOTTLENECK") @RequestParam(required = false) String direction) {
        return Result.success(strategyService.list(query, name, direction));
    }

    /**
     * 策略详情。
     *
     * @param id 策略主键
     * @return 策略视图
     */
    @Operation(summary = "策略详情")
    @GetMapping("/{id}")
    public Result<StrategyView> get(@PathVariable UUID id) {
        return Result.success(strategyService.get(id));
    }

    /**
     * 创建策略。
     *
     * @param request 创建请求
     * @return 创建后的策略
     */
    @Operation(summary = "创建策略", description = "rules 省略时使用 8 条默认权重。")
    @PostMapping
    public Result<StrategyView> create(@Valid @RequestBody StrategyCreateRequest request) {
        return Result.success(strategyService.create(request.toCommand()));
    }

    /**
     * 更新策略（全量替换 rules）。
     *
     * @param id 策略主键
     * @param request 更新请求
     * @return 更新后的策略
     */
    @Operation(summary = "更新策略", description = "全量替换 rules，需携带 version 乐观锁。")
    @PutMapping("/{id}")
    public Result<StrategyView> update(@PathVariable UUID id,
                                       @Valid @RequestBody StrategyUpdateRequest request) {
        return Result.success(strategyService.update(id, request.toCommand()));
    }

    /**
     * 删除策略。
     *
     * @param id 策略主键
     * @return 空响应
     */
    @Operation(summary = "删除策略")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        strategyService.delete(id);
        return Result.success();
    }

    /**
     * 克隆策略。
     *
     * @param id 源策略主键
     * @param request 克隆请求
     * @return 克隆后的策略
     */
    @Operation(summary = "克隆策略", description = "复制源策略全部规则并生成下一个业务版本。")
    @PostMapping("/{id}/clone")
    public Result<StrategyView> clone(@PathVariable UUID id,
                                      @RequestBody(required = false) StrategyCloneRequest request) {
        return Result.success(strategyService.clone(id, request == null ? null : request.toCommand()));
    }

    /**
     * 导出策略。
     *
     * @param id 策略主键
     * @return JSON 载荷
     */
    @Operation(summary = "导出策略")
    @GetMapping("/{id}/export")
    public Result<StrategyExportPayload> export(@PathVariable UUID id) {
        return Result.success(strategyService.export(id));
    }

    /**
     * 导入策略。
     *
     * @param payload 导入载荷
     * @return 创建后的策略
     */
    @Operation(summary = "导入策略", description = "按导出载荷创建新策略，规则校验口径与创建一致。")
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<StrategyView> importStrategy(@RequestBody StrategyExportPayload payload) {
        return Result.success(strategyService.importStrategy(payload));
    }
}
