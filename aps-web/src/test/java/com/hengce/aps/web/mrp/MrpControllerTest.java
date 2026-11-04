package com.hengce.aps.web.mrp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.mrp.InventoryUpsertResult;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpAnalysisService;
import com.hengce.aps.service.mrp.MrpStatus;
import com.hengce.aps.web.advice.RestExceptionHandler;
import com.hengce.aps.web.controller.mrp.InventoryController;
import com.hengce.aps.web.controller.mrp.MrpController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MRP / 库存控制器契约测试：路由、统一响应结构与错误码映射，不依赖真实数据库。
 */
class MrpControllerTest {

    private MrpAnalysisService mrpAnalysisService;

    private InventoryService inventoryService;

    private MockMvc mockMvc;

    private UUID sandboxId;

    @BeforeEach
    void setUp() {
        mrpAnalysisService = mock(MrpAnalysisService.class);
        inventoryService = mock(InventoryService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MrpController(mrpAnalysisService), new InventoryController(inventoryService))
                .setControllerAdvice(new RestExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        sandboxId = UUID.randomUUID();
    }

    @Test
    void analysisReturnsC25Fields() throws Exception {
        MrpAnalysisResponse response = new MrpAnalysisResponse(sandboxId,
                OffsetDateTime.of(2026, 11, 4, 4, 0, 0, 0, ZoneOffset.UTC), MrpStatus.SHORTAGE,
                true, true, true, 1, List.of(),
                List.of(new MrpAnalysisResponse.MrpOrderResult("SO-001", "FG-01", new BigDecimal("10"),
                        LocalDate.of(2026, 11, 10), false,
                        List.of(new MrpAnalysisResponse.MrpOrderShortage("RM-01", "原料",
                                new BigDecimal("10.000000"), new BigDecimal("4.000000"),
                                new BigDecimal("6.000000"))))),
                List.of(new MrpAnalysisResponse.MrpShortage("RM-01", "原料", new BigDecimal("10.000000"),
                        new BigDecimal("4.000000"), new BigDecimal("6.000000"),
                        OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC),
                        List.of("SO-001"),
                        OffsetDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneOffset.UTC),
                        new BigDecimal("6.000000"), null)));
        when(mrpAnalysisService.analyze(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/mrp/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sandboxId\":\"" + sandboxId
                                + "\",\"orderNos\":[\"SO-001\"],\"blocking\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SHORTAGE"))
                .andExpect(jsonPath("$.data.blocked").value(true))
                .andExpect(jsonPath("$.data.orders[0].orderNo").value("SO-001"))
                .andExpect(jsonPath("$.data.orders[0].shortages[0].materialCode").value("RM-01"))
                .andExpect(jsonPath("$.data.shortages[0].earliestRequiredAt").value("2026-11-10T00:00:00Z"));
    }

    @Test
    void analysisRejectsMissingSandboxId() throws Exception {
        mockMvc.perform(post("/api/v1/mrp/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNos\":[\"SO-001\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void inventoryUpsertReturnsCounts() throws Exception {
        when(inventoryService.upsert(any())).thenReturn(new InventoryUpsertResult(1, 0, 1));

        mockMvc.perform(put("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"materialCode\":\"RM-01\",\"warehouseCode\":\"DEFAULT\","
                                + "\"onHandQty\":10,\"safetyStock\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void inventoryNegativeQuantityMapsTo422() throws Exception {
        when(inventoryService.upsert(any())).thenThrow(
                new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "onHandQty 不能为负: RM-01"));

        mockMvc.perform(put("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"materialCode\":\"RM-01\",\"onHandQty\":-1}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(42200));
    }
}
