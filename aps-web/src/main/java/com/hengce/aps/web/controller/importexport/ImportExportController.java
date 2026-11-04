package com.hengce.aps.web.controller.importexport;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.importexport.EntityDataExporter;
import com.hengce.aps.service.importexport.ExcelDataWriter;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel 导入导出通用接口，契约 C26（M2-BD-05）。
 *
 * <p>9 类实体共用同一组端点：{@code GET /{entity}/import/template} 下载模板，
 * {@code POST /{entity}/import} 预览或执行。错误行统一返回 {@code {row, field, code, message}}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "导入导出", description = "9 类实体 Excel 模板下载与导入（M2-BD-05）")
public class ImportExportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportService importService;

    private final EntityDataExporter entityDataExporter;

    /**
     * 构造导入导出控制器。
     *
     * @param importService 导入服务
     * @param entityDataExporter 导出服务
     */
    public ImportExportController(ImportService importService, EntityDataExporter entityDataExporter) {
        this.importService = importService;
        this.entityDataExporter = entityDataExporter;
    }

    /**
     * 下载实体导入模板。
     *
     * @param entity 实体 slug
     * @return xlsx 文件
     */
    @Operation(summary = "下载导入模板", description = "状态：已实现。返回可直接被 Excel 打开的 xlsx。")
    @GetMapping({"/{entity}/import/template", "/import/{entity}/template"})
    public ResponseEntity<byte[]> template(@PathVariable String entity) {
        ImportEntity resolved = importService.resolveEntity(entity);
        byte[] content = importService.template(resolved);
        String fileName = importService.templateFileName(resolved);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Template-Filename", URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .body(content);
    }

    /**
     * 预览或执行导入。
     *
     * @param entity 实体 slug
     * @param mode preview=预览，execute=执行
     * @param token 幂等令牌，执行模式下重复提交同一令牌返回首次结果
     * @param file xlsx 文件
     * @return 导入结果
     */
    @Operation(summary = "导入 Excel",
            description = "状态：已实现。mode=preview 只校验；mode=execute 全部行通过后落库。"
                    + "兼容路径 /import/{entity} 用于绕开 M1 遗留的 /materials/import 占位映射。")
    @PostMapping(value = {"/{entity}/import", "/import/{entity}"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportResult> importFile(
            @PathVariable String entity,
            @Parameter(description = "preview/execute") @RequestParam(defaultValue = "preview") String mode,
            @Parameter(description = "幂等令牌，8-128 位") @RequestHeader(value = "X-Import-Token",
                    required = false) String token,
            @RequestPart("file") MultipartFile file) throws IOException {
        ImportEntity resolved = importService.resolveEntity(entity);
        ImportMode importMode = parseMode(mode);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入文件不能为空");
        }
        ImportResult result = importService.importFile(resolved, importMode, token, file.getInputStream(),
                file.getSize());
        return Result.success(result);
    }

    /**
     * 导出实体数据。
     *
     * @param entity 实体 slug
     * @return xlsx 文件
     */
    @Operation(summary = "导出数据", description = "状态：已实现。列顺序与导入模板一致，可直接回填导入。")
    @GetMapping({"/{entity}/export", "/export/{entity}"})
    public ResponseEntity<byte[]> export(@PathVariable String entity) {
        ImportEntity resolved = importService.resolveEntity(entity);
        byte[] content = entityDataExporter.export(resolved);
        String fileName = ExcelDataWriter.fileName(resolved);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .body(content);
    }

    private ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank() || "preview".equalsIgnoreCase(mode)) {
            return ImportMode.PREVIEW;
        }
        if ("execute".equalsIgnoreCase(mode)) {
            return ImportMode.EXECUTE;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "mode 只支持 preview/execute");
    }
}
