package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束权重化验证：默认关闭时保持 M1 仅硬约束得分；激活后权重直接改变软得分。
 */
class WeightedSoftConstraintTest {

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    @Test
    void disabledSoftConstraintsKeepSoftScoreZero() {
        ScheduleSolution solution = new TimefoldSolverAdapter().solve(frozenSingleTaskProblem(), config());

        assertEquals(0L, solution.score().softScore().longValueExact());
        assertEquals(0L, solution.score().hardScore().longValueExact());
    }

    @Test
    void weightsChangeSoftScoreDeterministically() {
        SoftConstraintWeights.activate(weights(ConstraintCodes.MIN_SETUP_TIME));
        long setupScore = solveSoftScore();

        SoftConstraintWeights.activate(weights(ConstraintCodes.MIN_MAKESPAN));
        long makespanScore = solveSoftScore();

        assertTrue(setupScore < 0, "换型权重应产生负软得分");
        assertTrue(makespanScore < 0, "总完工时间权重应产生负软得分");
        assertNotEquals(setupScore, makespanScore, "不同权重应改变软得分");
    }

    @Test
    void rejectsWeightSetThatDoesNotSumToHundred() {
        Map<String, Integer> invalid = weights(ConstraintCodes.MIN_TARDINESS);
        invalid.put(ConstraintCodes.EDD, 15);

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> SoftConstraintWeights.activate(invalid));
        assertEquals("软约束权重之和必须为 100，实际为 115", exception.getMessage());
    }

    private long solveSoftScore() {
        ScheduleSolution solution = new TimefoldSolverAdapter().solve(frozenSingleTaskProblem(), config());
        return solution.score().softScore().longValueExact();
    }

    private static Map<String, Integer> weights(String enabledCode) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String code : ConstraintCodes.SOFT_RULE_CODES) {
            weights.put(code, code.equals(enabledCode) ? 100 : 0);
        }
        return weights;
    }

    private static ScheduleProblem frozenSingleTaskProblem() {
        UUID primaryResourceId = UUID.randomUUID();
        UUID secondaryResourceId = UUID.randomUUID();
        SchedulingResource primary = resource(primaryResourceId, "R-CNC");
        SchedulingResource secondary = resource(secondaryResourceId, "R-ASSEMBLY");
        LocalDateTime pinnedStart = LocalDateTime.of(2026, 1, 5, 8, 0);
        TaskAssignment frozen = new TaskAssignment(
                null,
                UUID.randomUUID(),
                "SO-FROZEN",
                10,
                "工序 10",
                null,
                primaryResourceId,
                "R-CNC",
                List.of(),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC).plusSeconds(7800),
                BigDecimal.valueOf(12),
                0,
                "FROZEN",
                true,
                1);
        SchedulingOrder order = new SchedulingOrder(
                frozen.orderId(),
                frozen.orderNo(),
                "MAT-SO-FROZEN",
                BigDecimal.valueOf(12),
                LocalDate.of(2026, 1, 10),
                "NORMAL",
                "FROZEN",
                UUID.randomUUID(),
                List.of(new SchedulingOperation(10, "OP-10", "工序 10", null,
                        BigDecimal.valueOf(600), BigDecimal.valueOf(600), primaryResourceId,
                        null, List.of(), true)));
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(order),
                List.of(primary, secondary),
                List.of(frozen),
                List.of());
    }

    private static SolveConfig config() {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, Map.of());
    }

    private static SchedulingResource resource(UUID resourceId, String code) {
        return new SchedulingResource(resourceId, code, "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
    }
}
