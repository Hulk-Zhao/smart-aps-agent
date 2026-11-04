package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束权重端到端对比（真跑 Timefold，不用 mock）。
 *
 * <p>同一道题、同一份数据：单笔冻结任务固定在 2026-01-05 08:00、加工 30 分钟，
 * 订单交期 2026-01-04，因此超期恒为 510 分钟；同资源仅此一个任务，EDD/SPT/同品连续/
 * 优先级尊重均无相邻对（贡献 0），换型为 0，负载小时平方为 0。</p>
 *
 * <p>只调整 {@code MIN_TARDINESS} 权重 0 → 40，其余四条各下调 10（合计仍为 100）：</p>
 * <ul>
 *   <li>权重 0：软得分仅由 {@code MIN_MAKESPAN}(16) * 30 分钟构成，期望 {@code -480}；</li>
 *   <li>权重 40：{@code -40 * 510 - 6 * 30}，期望 {@code -20580}；</li>
 * </ul>
 *
 * <p>方向断言：提高“最小超期”权重后，超期惩罚放大，softScore 必须更负。</p>
 */
class SoftWeightsEndToEndTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005a1");

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000005b1");

    private static final LocalDateTime PINNED_START = LocalDateTime.of(2026, 1, 5, 8, 0);

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    @Test
    void raisingTardinessWeightFromZeroToFortyMakesSoftScoreMoreNegative() {
        long baseline = solveSoftScore(weights(0));
        long weighted = solveSoftScore(weights(40));

        assertEquals(-480L, baseline, "权重 0 时仅剩总完工时间 16 * 30 分钟");
        assertEquals(-20580L, weighted, "权重 40 时超期 40 * 510 分钟 + 总完工时间 6 * 30 分钟");
        assertTrue(weighted < baseline,
                "提高超期权重后超期惩罚放大，softScore 必须更负: baseline=" + baseline
                        + ", weighted=" + weighted);
        assertFalse(SoftConstraintWeights.isEnabled(), "求解结束必须 disable()，避免污染其他任务");
    }

    private long solveSoftScore(Map<String, Integer> weights) {
        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem(), config(Map.of("softWeights", weights)));
        assertEquals("SOLVED", solution.status(), "冻结任务无可行解移动，必须稳定可行");
        assertEquals(0L, solution.score().hardScore().longValueExact());
        return solution.score().softScore().longValueExact();
    }

    /**
     * 权重：{@code MIN_TARDINESS=0} 时 EDD16/SPT8/同品12/换型12/负载12/优先级24/完工16（合计 100）；
     * {@code MIN_TARDINESS=40} 时由 EDD、负载、优先级、完工四条各下调 10 腾出 40，合计仍为 100。
     */
    private static Map<String, Integer> weights(int tardiness) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("MIN_TARDINESS", tardiness);
        if (tardiness == 0) {
            weights.put("EDD", 16);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 12);
            weights.put("PRIORITY_RESPECT", 24);
            weights.put("MIN_MAKESPAN", 16);
        } else {
            weights.put("EDD", 6);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 2);
            weights.put("PRIORITY_RESPECT", 14);
            weights.put("MIN_MAKESPAN", 6);
        }
        return weights;
    }

    private static ScheduleProblem problem() {
        SchedulingResource resource = new SchedulingResource(
                RESOURCE_ID, "R-E2E", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
        SchedulingOperation operation = new SchedulingOperation(
                10, "OP-10", "工序 10", null, BigDecimal.valueOf(1800), BigDecimal.ZERO,
                RESOURCE_ID, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(
                ORDER_ID, "SO-E2E", "MAT-E2E", "FAM-E2E", BigDecimal.ONE,
                LocalDate.of(2026, 1, 4), "NORMAL", "FROZEN", UUID.randomUUID(), List.of(operation));
        OffsetDateTime start = PINNED_START.atOffset(ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(
                null, ORDER_ID, "SO-E2E", 10, "工序 10", null,
                RESOURCE_ID, "R-E2E", List.of(),
                start, start, start, start.plusSeconds(1800),
                BigDecimal.ONE, 0, "FROZEN", true, 1);
        return new ScheduleProblem(
                "tenant-e2e", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of());
    }

    private static SolveConfig config(Map<String, Object> parameters) {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, parameters);
    }
}
