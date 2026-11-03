package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 物料与主数据域占位契约（materials 分组）。
 *
 * <p>所有端点当前未实现，调用返回 501 / code=50100；字段契约见
 * {@code docs/api/openapi/materials.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "物料与主数据", description = "物料、BOM、资源、日历、工艺路线、MRP（P0 占位契约）")
public class MaterialsContractController {

    /**
     * 物料列表。
     *
     * @param query 分页参数
     * @param type 物料类型
     * @return 占位响应
     */
    @Operation(summary = "物料列表", description = "状态：占位。分页参数 page/size/sort。")
    @GetMapping("/materials")
    public Result<Void> listMaterials(@ParameterObject PageQuery query,
                                      @Parameter(description = "FINISHED/SEMI/RAW/AUX/PACK")
                                      @RequestParam(required = false) String type) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建物料。
     *
     * @param body 物料字段，见 materials.yaml MaterialCreateRequest
     * @return 占位响应
     */
    @Operation(summary = "创建物料", description = "状态：占位。请求字段见 MaterialCreateRequest。")
    @PostMapping("/materials")
    public Result<Void> createMaterial(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 物料详情。
     *
     * @param id 物料业务编码 code
     * @return 占位响应
     */
    @Operation(summary = "物料详情", description = "状态：占位。路径参数为 material.code。")
    @GetMapping("/materials/{id}")
    public Result<Void> getMaterial(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 更新物料。
     *
     * @param id 物料业务编码
     * @param body 更新字段
     * @return 占位响应
     */
    @Operation(summary = "更新物料", description = "状态：占位。")
    @PutMapping("/materials/{id}")
    public Result<Void> updateMaterial(@PathVariable String id,
                                       @RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 删除物料（逻辑删除）。
     *
     * @param id 物料业务编码
     * @return 占位响应
     */
    @Operation(summary = "删除物料", description = "状态：占位。")
    @DeleteMapping("/materials/{id}")
    public Result<Void> deleteMaterial(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * Excel 导入物料。
     *
     * @param body 导入参数
     * @return 占位响应
     */
    @Operation(summary = "导入物料", description = "状态：占位。multipart 文件字段 file。")
    @PostMapping("/materials/import")
    public Result<Void> importMaterials(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * BOM 列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "BOM 列表", description = "状态：占位。")
    @GetMapping("/boms")
    public Result<Void> listBoms(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建 BOM。
     *
     * @param body BOM 字段
     * @return 占位响应
     */
    @Operation(summary = "创建 BOM", description = "状态：占位。请求字段见 BomCreateRequest。")
    @PostMapping("/boms")
    public Result<Void> createBom(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * BOM 树。
     *
     * @param id BOM 主键 UUID
     * @return 占位响应
     */
    @Operation(summary = "BOM 树", description = "状态：占位。")
    @GetMapping("/boms/{id}/tree")
    public Result<Void> bomTree(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 激活 BOM 版本。
     *
     * @param id BOM 主键 UUID
     * @return 占位响应
     */
    @Operation(summary = "激活 BOM 版本", description = "状态：占位。同一父件仅允许一个 ACTIVE 版本。")
    @PostMapping("/boms/{id}/activate")
    public Result<Void> activateBom(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 资源列表。
     *
     * @param query 分页参数
     * @param type 资源类型
     * @return 占位响应
     */
    @Operation(summary = "资源列表", description = "状态：占位。type=PRIMARY/SECONDARY/HUMAN/TOOL。")
    @GetMapping("/resources")
    public Result<Void> listResources(@ParameterObject PageQuery query,
                                      @Parameter(description = "PRIMARY/SECONDARY/HUMAN/TOOL")
                                      @RequestParam(required = false) String type) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建资源。
     *
     * @param body 资源字段
     * @return 占位响应
     */
    @Operation(summary = "创建资源", description = "状态：占位。请求字段见 ResourceCreateRequest。")
    @PostMapping("/resources")
    public Result<Void> createResource(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 设置资源维护计划。
     *
     * @param id 资源业务编码
     * @param body 维护计划
     * @return 占位响应
     */
    @Operation(summary = "资源维护计划", description = "状态：占位。")
    @PostMapping("/resources/{id}/maintenance")
    public Result<Void> maintainResource(@PathVariable String id,
                                         @RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 日历列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "日历列表", description = "状态：占位。")
    @GetMapping("/calendars")
    public Result<Void> listCalendars(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建日历。
     *
     * @param body 日历字段
     * @return 占位响应
     */
    @Operation(summary = "创建日历", description = "状态：占位。请求字段见 CalendarCreateRequest。")
    @PostMapping("/calendars")
    public Result<Void> createCalendar(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 可用工时查询。
     *
     * @param id 日历主键 UUID
     * @param from 起始时间（ISO-8601 UTC）
     * @param to 结束时间（ISO-8601 UTC）
     * @return 占位响应
     */
    @Operation(summary = "可用工时", description = "状态：占位。时间参数为 ISO-8601 UTC。")
    @GetMapping("/calendars/{id}/available")
    public Result<Void> availableWorkingTime(@PathVariable String id,
                                             @RequestParam String from,
                                             @RequestParam String to) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 工艺路线列表。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "工艺路线列表", description = "状态：占位。")
    @GetMapping("/routings")
    public Result<Void> listRoutings(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 创建工艺路线。
     *
     * @param body 工艺路线字段
     * @return 占位响应
     */
    @Operation(summary = "创建工艺路线", description = "状态：占位。请求字段见 RoutingCreateRequest。")
    @PostMapping("/routings")
    public Result<Void> createRouting(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 工艺路线 DAG 图。
     *
     * @param id 工艺路线主键 UUID
     * @return 占位响应
     */
    @Operation(summary = "工艺路线 DAG", description = "状态：占位。返回 nodes/edges。")
    @GetMapping("/routings/{id}/graph")
    public Result<Void> routingGraph(@PathVariable String id) {
        throw ContractSupport.notImplemented();
    }

    /**
     * MRP 齐套分析。
     *
     * @param body 分析参数
     * @return 占位响应
     */
    @Operation(summary = "MRP 齐套分析", description = "状态：占位。")
    @PostMapping("/mrp/analysis")
    public Result<Void> mrpAnalysis(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * MRP 补料建议。
     *
     * @param query 分页参数
     * @return 占位响应
     */
    @Operation(summary = "MRP 补料建议", description = "状态：占位。")
    @GetMapping("/mrp/suggestions")
    public Result<Void> mrpSuggestions(@ParameterObject PageQuery query) {
        throw ContractSupport.notImplemented();
    }
}
