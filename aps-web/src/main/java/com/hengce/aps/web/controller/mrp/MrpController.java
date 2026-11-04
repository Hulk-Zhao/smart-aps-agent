package com.hengce.aps.web.controller.mrp;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.mrp.MrpAnalysisCommand;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MRP 齐套分析接口（M2 BD-01 / C25）。
 *
 * <p>{@code POST /api/v1/mrp/analysis}：输入 sandboxId、可选 orderNos、includeSuggestions、
 * blocking；输出 FULL / PARTIAL / SHORTAGE、按订单与按物料的缺料明细及 earliestRequiredAt。</p>
 */
@RestController
@RequestMapping("/api/v1/mrp")
@Tag(name = "MRP", description = "BOM 多级展开、库存/在途齐套、缺料报告（BD-01 已实现）")
public class MrpController {

    private final MrpAnalysisService mrpAnalysisService;

    /**
     * 构造控制器。
     *
     * @param mrpAnalysisService MRP 分析服务
     */
    public MrpController(MrpAnalysisService mrpAnalysisService) {
        this.mrpAnalysisService = mrpAnalysisService;
    }

    /**
     * 执行 MRP 齐套分析。
     *
     * @param request 分析请求
     * @return 齐套分析结果
     */
    @Operation(summary = "MRP 齐套分析",
            description = "状态：已实现。缺料返回完整明细；blocking=true 时 blocked 标记是否拦截排产。")
    @PostMapping("/analysis")
    public Result<MrpAnalysisResponse> analyze(@Valid @RequestBody MrpAnalysisRequest request) {
        return Result.success(mrpAnalysisService.analyze(request.toCommand()));
    }

    /**
     * MRP 齐套分析请求体。
     *
     * @param sandboxId 排产场景标识
     * @param orderNos 指定订单号，空表示全部可排产订单
     * @param includeSuggestions 是否输出补料建议，默认 true
     * @param blocking 是否按缺料阻塞排产，默认 false
     */
    public record MrpAnalysisRequest(@NotNull UUID sandboxId, List<String> orderNos,
                                     Boolean includeSuggestions, Boolean blocking) {

        /**
         * 转换为服务层命令。
         *
         * @return 分析命令
         */
        MrpAnalysisCommand toCommand() {
            return new MrpAnalysisCommand(sandboxId, orderNos,
                    includeSuggestions == null || includeSuggestions,
                    blocking != null && blocking);
        }
    }
}
