package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveHandle;
import com.hengce.aps.solver.api.SolverEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timefold 适配器的契约与空问题路径测试，不依赖外部数据。
 */
class TimefoldSolverAdapterTest {

    private final TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();

    @Test
    void exposesTimefoldEngine() {
        assertEquals(SolverEngine.TIMEFOLD, adapter.engine());
    }

    @Test
    void emptyProblemReturnsSolvedWithoutAssignments() {
        ScheduleSolution solution = adapter.solve(emptyProblem(), config());

        assertEquals("SOLVED", solution.status());
        assertEquals(0, solution.assignments().size());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertEquals(0L, solution.score().softScore().longValueExact());
        assertTrue(solution.message().contains("无待排任务"));
    }

    @Test
    void solveRejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> adapter.solve(null, config()));
        assertThrows(NullPointerException.class, () -> adapter.solve(emptyProblem(), null));
    }

    @Test
    void asyncLifecycleRemainsUnimplemented() {
        SolveHandle handle = new SolveHandle(UUID.randomUUID(), SolverEngine.TIMEFOLD, "RUNNING");

        assertThrows(UnsupportedOperationException.class, () -> adapter.submitAsync(emptyProblem(), config()));
        assertThrows(UnsupportedOperationException.class, () -> adapter.terminateEarly(handle));
        assertThrows(UnsupportedOperationException.class, () -> adapter.getProgress(handle));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.solveIncremental(emptyProblem(), config(), null));
    }

    private static ScheduleProblem emptyProblem() {
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static SolveConfig config() {
        return new SolveConfig(
                "timefold",
                1,
                1,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }
}
