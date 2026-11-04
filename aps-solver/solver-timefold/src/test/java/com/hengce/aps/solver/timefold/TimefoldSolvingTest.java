package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timefold 真实求解测试：3 订单 / 2 资源，验证硬约束与时长。
 */
class TimefoldSolvingTest {

    private static final int SOLVE_SECONDS = 2;

    @Test
    void solvesSyntheticOrdersWithoutOverlapAndInPrecedence() {
        UUID resourceOneId = UUID.randomUUID();
        UUID resourceTwoId = UUID.randomUUID();
        SchedulingResource resourceOne = resource(resourceOneId, "R-CNC");
        SchedulingResource resourceTwo = resource(resourceTwoId, "R-ASSEMBLY");
        List<SchedulingOrder> orders = List.of(
                order("SO-1", resourceOneId, resourceTwoId),
                order("SO-2", resourceOneId, resourceTwoId),
                order("SO-3", resourceOneId, resourceTwoId));
        ScheduleProblem problem = problem(orders, List.of(resourceOne, resourceTwo), List.of());

        ScheduleSolution solution = new TimefoldSolverAdapter().solve(problem, config());

        assertEquals("SOLVED", solution.status(), solution.message());
        assertEquals(6, solution.assignments().size());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertEquals(0L, solution.score().softScore().longValueExact());
        assertTrue(solution.metrics().durationMs() > 0);
        assertNoOverlap(solution.assignments());
        assertPrecedence(solution.assignments());
        assertDurations(solution.assignments());
    }

    @Test
    void keepsFrozenTaskAtPinnedResourceAndTime() {
        UUID resourceOneId = UUID.randomUUID();
        UUID resourceTwoId = UUID.randomUUID();
        SchedulingResource resourceOne = resource(resourceOneId, "R-CNC");
        SchedulingResource resourceTwo = resource(resourceTwoId, "R-ASSEMBLY");
        SchedulingOrder order = order("SO-FROZEN", resourceOneId, resourceTwoId);
        LocalDateTime pinnedStart = LocalDateTime.now(ZoneOffset.UTC)
                .plusHours(2)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        TaskAssignment frozen = new TaskAssignment(
                null,
                order.orderId(),
                order.orderNo(),
                10,
                "工序 10",
                null,
                resourceOneId,
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
        ScheduleProblem problem = problem(
                List.of(order), List.of(resourceOne, resourceTwo), List.of(frozen));

        ScheduleSolution solution = new TimefoldSolverAdapter().solve(problem, config());

        assertEquals("SOLVED", solution.status(), solution.message());
        TaskAssignment frozenAssignment = solution.assignments().stream()
                .filter(assignment -> assignment.operationIndex() == 10)
                .findFirst()
                .orElseThrow();
        assertEquals(resourceOneId, frozenAssignment.resourceId());
        assertEquals(pinnedStart.atOffset(ZoneOffset.UTC), frozenAssignment.setupStartTime());
    }

    private static void assertNoOverlap(List<TaskAssignment> assignments) {
        Map<String, List<TaskAssignment>> byResource = assignments.stream()
                .collect(Collectors.groupingBy(TaskAssignment::resourceCode));
        for (List<TaskAssignment> resourceTasks : byResource.values()) {
            for (int first = 0; first < resourceTasks.size(); first++) {
                for (int second = first + 1; second < resourceTasks.size(); second++) {
                    assertFalse(overlaps(resourceTasks.get(first), resourceTasks.get(second)),
                            "资源上存在时间重叠");
                }
            }
        }
    }

    private static boolean overlaps(TaskAssignment first, TaskAssignment second) {
        return first.setupStartTime().isBefore(second.endTime())
                && second.setupStartTime().isBefore(first.endTime());
    }

    private static void assertPrecedence(List<TaskAssignment> assignments) {
        for (String orderNo : List.of("SO-1", "SO-2", "SO-3")) {
            List<TaskAssignment> orderTasks = new ArrayList<>(assignments.stream()
                    .filter(assignment -> assignment.orderNo().equals(orderNo))
                    .toList());
            orderTasks.sort((first, second) ->
                    Integer.compare(first.operationIndex(), second.operationIndex()));
            assertEquals(2, orderTasks.size());
            TaskAssignment previous = orderTasks.get(0);
            TaskAssignment next = orderTasks.get(1);
            assertEquals(10, previous.operationIndex());
            assertEquals(20, next.operationIndex());
            assertFalse(previous.endTime().isAfter(next.startTime()),
                    "前序工序结束后才能开始后序工序");
        }
    }

    private static void assertDurations(List<TaskAssignment> assignments) {
        for (TaskAssignment assignment : assignments) {
            assertNotNull(assignment.setupStartTime());
            assertNotNull(assignment.setupEndTime());
            assertNotNull(assignment.startTime());
            assertNotNull(assignment.endTime());
            assertEquals(600L, Duration.between(
                    assignment.setupStartTime(), assignment.setupEndTime()).getSeconds());
            assertEquals(7200L, Duration.between(
                    assignment.startTime(), assignment.endTime()).getSeconds());
            assertEquals(7800L, Duration.between(
                    assignment.setupStartTime(), assignment.endTime()).getSeconds());
            assertFalse(assignment.sequenceInResource() < 0);
        }
    }

    private static ScheduleProblem problem(
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozenAssignments) {
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                orders,
                resources,
                frozenAssignments,
                List.of());
    }

    private static SolveConfig config() {
        return new SolveConfig(
                "timefold",
                SOLVE_SECONDS,
                1,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }

    private static SchedulingResource resource(UUID resourceId, String code) {
        return new SchedulingResource(
                resourceId,
                code,
                "PRIMARY",
                null,
                "SECONDS_PER_UNIT",
                BigDecimal.ONE,
                null,
                BigDecimal.ONE,
                null,
                "ACTIVE");
    }

    private static SchedulingOrder order(String orderNo, UUID resourceOneId, UUID resourceTwoId) {
        return new SchedulingOrder(
                UUID.randomUUID(),
                orderNo,
                "MAT-" + orderNo,
                BigDecimal.valueOf(12),
                LocalDate.now(ZoneOffset.UTC).plusDays(3),
                "NORMAL",
                "UNFROZEN",
                UUID.randomUUID(),
                List.of(
                        operation(10, resourceOneId),
                        operation(20, resourceTwoId)));
    }

    private static SchedulingOperation operation(int operationIndex, UUID resourceId) {
        return new SchedulingOperation(
                operationIndex,
                "OP-" + operationIndex,
                "工序 " + operationIndex,
                null,
                BigDecimal.valueOf(600),
                BigDecimal.valueOf(600),
                resourceId,
                null,
                List.of(),
                false);
    }
}
