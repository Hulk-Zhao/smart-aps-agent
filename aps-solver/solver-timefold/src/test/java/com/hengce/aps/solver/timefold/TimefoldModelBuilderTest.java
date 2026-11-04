package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型构建的降级标记与 C12 输入校验。
 */
class TimefoldModelBuilderTest {

    @Test
    void missingCalendarDependencyAndMatrixSetFallbackFlags() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-FB", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId)));

        SchedulePlan plan = TimefoldModelBuilder.build(SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC")),
                List.of()));

        assertTrue(plan.isCalendarFallback());
        assertTrue(plan.isDependencyFallback());
        assertTrue(plan.isSetupMatrixFallback());
    }

    @Test
    void explicitCalendarDependenciesAndMatrixClearFallbackFlags() {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(8);
        SchedulingOrder order = SolverFixtures.order("SO-OK", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId,
                        List.of(new OperationDependency(10, "FS", 0)))));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC",
                        List.of(new AvailabilityWindow(from, to)))),
                List.of(),
                List.of(new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 600)));

        SchedulePlan plan = TimefoldModelBuilder.build(problem);

        assertFalse(plan.isCalendarFallback());
        assertFalse(plan.isDependencyFallback());
        assertFalse(plan.isSetupMatrixFallback());
        assertFalse(plan.getDependencies().isEmpty());
    }

    @Test
    void overlappingAvailabilityWindowsAreRejected() {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        SchedulingOrder order = SolverFixtures.order("SO-WIN", List.of(
                SolverFixtures.operation(10, resourceId)));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC", List.of(
                        new AvailabilityWindow(from, from.plusDays(2)),
                        new AvailabilityWindow(from.plusDays(1), from.plusDays(3))))),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> TimefoldModelBuilder.build(problem));
    }
}
