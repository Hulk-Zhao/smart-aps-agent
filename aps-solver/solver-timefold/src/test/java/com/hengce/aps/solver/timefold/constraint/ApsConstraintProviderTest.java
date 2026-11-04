package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.SolverFixtures;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 约束流端到端激活测试：用无法修复的冻结任务制造违反，断言求解被判定为不可行并输出稳定约束码；
 * 另用可行合成数据断言无归因、无降级。
 */
class ApsConstraintProviderTest {

    private static final int SOLVE_SECONDS = 2;

    @Test
    void overlappingPrimaryResourceIsInfeasible() {
        UUID resourceId = UUID.randomUUID();
        SchedulingResource resource = SolverFixtures.resource(resourceId, "R-CNC");
        SchedulingOrder first = SolverFixtures.order("SO-A", List.of(
                SolverFixtures.operation(10, resourceId)));
        SchedulingOrder second = SolverFixtures.order("SO-B", List.of(
                SolverFixtures.operation(10, resourceId)));
        LocalDateTime start = SolverFixtures.baseTime(2);
        List<TaskAssignment> frozen = List.of(
                SolverFixtures.frozen(first, 10, resourceId, "R-CNC", start, 7800, List.of()),
                SolverFixtures.frozen(second, 10, resourceId, "R-CNC", start, 7800, List.of()));

        ScheduleSolution solution = solve(
                List.of(first, second), List.of(resource), frozen);

        assertInfeasibleWithCode(solution, ConstraintCodes.RESOURCE_NO_OVERLAP);
    }

    @Test
    void overlappingSecondaryResourceIsInfeasible() {
        UUID primaryOneId = UUID.randomUUID();
        UUID primaryTwoId = UUID.randomUUID();
        UUID secondaryId = UUID.randomUUID();
        SchedulingOrder first = SolverFixtures.order("SO-A", List.of(
                SolverFixtures.operation(10, primaryOneId, List.of(secondaryId), List.of())));
        SchedulingOrder second = SolverFixtures.order("SO-B", List.of(
                SolverFixtures.operation(10, primaryTwoId, List.of(secondaryId), List.of())));
        LocalDateTime start = SolverFixtures.baseTime(2);
        List<TaskAssignment> frozen = List.of(
                SolverFixtures.frozen(first, 10, primaryOneId, "R-CNC", start, 7800,
                        List.of(secondaryId)),
                SolverFixtures.frozen(second, 10, primaryTwoId, "R-PAINT", start, 7800,
                        List.of(secondaryId)));

        ScheduleSolution solution = solve(
                List.of(first, second),
                List.of(
                        SolverFixtures.resource(primaryOneId, "R-CNC"),
                        SolverFixtures.resource(primaryTwoId, "R-PAINT"),
                        SolverFixtures.secondaryResource(secondaryId, "R-TOOL")),
                frozen);

        assertInfeasibleWithCode(solution, ConstraintCodes.RESOURCE_NO_OVERLAP);
    }

    @Test
    void taskOutsideAvailabilityWindowIsInfeasible() {
        UUID resourceId = UUID.randomUUID();
        LocalDateTime start = SolverFixtures.baseTime(2);
        OffsetDateTime windowStart = start.plusHours(1).atOffset(ZoneOffset.UTC);
        OffsetDateTime windowEnd = start.plusHours(5).atOffset(ZoneOffset.UTC);
        SchedulingResource resource = SolverFixtures.resource(resourceId, "R-CNC",
                List.of(new AvailabilityWindow(windowStart, windowEnd)));
        SchedulingOrder order = SolverFixtures.order("SO-CAL", List.of(
                SolverFixtures.operation(10, resourceId)));
        List<TaskAssignment> frozen = List.of(
                SolverFixtures.frozen(order, 10, resourceId, "R-CNC", start, 7800, List.of()));

        ScheduleSolution solution = solve(List.of(order), List.of(resource), frozen);

        assertInfeasibleWithCode(solution, ConstraintCodes.CALENDAR_AVAILABILITY);
    }

    @Test
    void finishStartDependencyViolationIsInfeasible() {
        UUID resourceOneId = UUID.randomUUID();
        UUID resourceTwoId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-DEP", List.of(
                SolverFixtures.operation(10, resourceOneId),
                SolverFixtures.operation(20, resourceTwoId,
                        List.of(new OperationDependency(10, "FS", 120)))));
        LocalDateTime start = SolverFixtures.baseTime(2);
        List<TaskAssignment> frozen = List.of(
                SolverFixtures.frozen(order, 10, resourceOneId, "R-CNC", start, 7800, List.of()),
                SolverFixtures.frozen(order, 20, resourceTwoId, "R-PAINT", start, 7800, List.of()));

        ScheduleSolution solution = solve(
                List.of(order),
                List.of(
                        SolverFixtures.resource(resourceOneId, "R-CNC"),
                        SolverFixtures.resource(resourceTwoId, "R-PAINT")),
                frozen);

        assertInfeasibleWithCode(solution, ConstraintCodes.OPERATION_DEPENDENCY);
    }

    @Test
    void startStartAndFinishFinishDependenciesAreRespected() {
        UUID resourceOneId = UUID.randomUUID();
        UUID resourceTwoId = UUID.randomUUID();
        UUID resourceThreeId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-SSFF", List.of(
                SolverFixtures.operation(10, resourceOneId),
                SolverFixtures.operation(20, resourceTwoId,
                        List.of(new OperationDependency(10, "SS", 120))),
                SolverFixtures.operation(30, resourceThreeId,
                        List.of(new OperationDependency(20, "FF", 0)))));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(
                        SolverFixtures.resource(resourceOneId, "R-CNC"),
                        SolverFixtures.resource(resourceTwoId, "R-PAINT"),
                        SolverFixtures.resource(resourceThreeId, "R-ASSEMBLY")),
                List.of());

        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem, SolverFixtures.config(SOLVE_SECONDS));

        assertEquals("SOLVED", solution.status(), solution.message());
        TaskAssignment top = byOperation(solution, 10);
        TaskAssignment middle = byOperation(solution, 20);
        TaskAssignment last = byOperation(solution, 30);
        assertFalse(middle.setupStartTime().isBefore(
                top.setupStartTime().plusMinutes(120)), "SS 滞后未生效");
        assertFalse(last.endTime().isBefore(middle.endTime()), "FF 约束未生效");
    }

    @Test
    void feasibleSyntheticPlanHasNoJustificationsAndNoFallback() {
        UUID resourceOneId = UUID.randomUUID();
        UUID resourceTwoId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(8);
        List<AvailabilityWindow> windows = List.of(new AvailabilityWindow(from, to));
        List<SchedulingOrder> orders = List.of(
                SolverFixtures.order("SO-1", List.of(
                        SolverFixtures.operation(10, resourceOneId),
                        SolverFixtures.operation(20, resourceTwoId,
                                List.of(new OperationDependency(10, "FS", 0)))))
        );
        ScheduleProblem problem = SolverFixtures.problem(
                orders,
                List.of(
                        SolverFixtures.resource(resourceOneId, "R-CNC", windows),
                        SolverFixtures.resource(resourceTwoId, "R-PAINT", windows)),
                List.of(),
                List.of(new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 600)));

        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem, SolverFixtures.config(SOLVE_SECONDS));

        assertEquals("SOLVED", solution.status(), solution.message());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertTrue(solution.justifications().isEmpty());
        assertFalse((Boolean) solution.metrics().extra().get("calendarFallback"));
        assertFalse((Boolean) solution.metrics().extra().get("dependencyFallback"));
        assertFalse((Boolean) solution.metrics().extra().get("setupMatrixFallback"));
    }

    private static ScheduleSolution solve(
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozen) {
        return new TimefoldSolverAdapter().solve(
                SolverFixtures.problem(orders, resources, frozen),
                SolverFixtures.config(SOLVE_SECONDS));
    }

    private static TaskAssignment byOperation(ScheduleSolution solution, int operationIndex) {
        return solution.assignments().stream()
                .filter(assignment -> assignment.operationIndex() == operationIndex)
                .findFirst()
                .orElseThrow();
    }

    private static void assertInfeasibleWithCode(ScheduleSolution solution, String constraintCode) {
        assertEquals("INFEASIBLE", solution.status(), solution.message());
        assertTrue(solution.score().hardScore().signum() < 0);
        List<String> codes = solution.justifications().stream()
                .map(ConstraintJustification::constraintCode)
                .toList();
        assertTrue(codes.contains(constraintCode), "justifications=" + solution.justifications());
    }
}
