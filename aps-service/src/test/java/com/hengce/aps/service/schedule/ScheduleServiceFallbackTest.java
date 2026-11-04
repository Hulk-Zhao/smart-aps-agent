package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.service.strategy.StrategyRuleView;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 引擎降级单测（M2-SE-06）：异常、超时、引擎不可用写降级标记；校验失败不降级。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceFallbackTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    @Mock
    private ScheduleProblemAssembler assembler;

    @Mock
    private ScheduleResultWriter resultWriter;

    @Mock
    private SolveTaskRepository solveTaskRepository;

    @Mock
    private StrategyService strategyService;

    @Test
    void engineExceptionShouldWriteEngineFallbackMarker() {
        ScheduleService service = new ScheduleService(List.of(new ThrowingAdapter()), assembler,
                resultWriter, solveTaskRepository, strategyService, true);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                new ThrowingAdapter(), () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertTrue(Boolean.TRUE.equals(outcome.metrics().get("engineFallback")));
        assertEquals("ENGINE_ERROR", outcome.metrics().get("engineFallbackReason"));
        assertEquals("HARD_ONLY", outcome.metrics().get("fallbackMode"));
        assertTrue(outcome.metrics().get("fallbackAt") != null);
    }

    @Test
    void engineTimeoutStatusShouldMapToTimeoutFallback() {
        SolverAdapter timeoutAdapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return new ScheduleSolution("TIMEOUT", null, List.of(), List.of(), metrics(), "time limit");
            }
        };
        ScheduleService service = new ScheduleService(List.of(timeoutAdapter), assembler,
                resultWriter, solveTaskRepository, strategyService, true);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                timeoutAdapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.TIMEOUT, outcome.status());
        assertEquals("TIMEOUT", outcome.metrics().get("engineFallbackReason"));
    }

    @Test
    void missingAdapterShouldReportSolverUnavailable() {
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                null, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertEquals("SOLVER_UNAVAILABLE", outcome.metrics().get("engineFallbackReason"));
    }

    @Test
    void validationFailureShouldNotWriteFallbackMarker() {
        ScheduleService service = new ScheduleService(List.of(new ThrowingAdapter()), assembler,
                resultWriter, solveTaskRepository, strategyService, true);
        when(assembler.assemble(any(), any()))
                .thenThrow(new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "租户下没有可用资源，无法排产"));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                new ThrowingAdapter(), () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertTrue(outcome.validationFailure());
        assertTrue(Boolean.TRUE.equals(outcome.metrics().get("validationFailure")));
        assertFalse(Boolean.TRUE.equals(outcome.metrics().get("engineFallback")));
        assertNull(outcome.metrics().get("engineFallbackReason"));
    }

    @Test
    void solvedShouldPersistWithSolveTaskIdAndBestScore() {
        ScheduleSolution solution = new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, new BigDecimal("-10"), "0hard/-10soft"),
                List.of(), List.of(), metrics(), "ok");
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return solution;
            }
        };
        ScheduleService service = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true);
        when(assembler.assemble(any(), any())).thenReturn(assembled());
        when(resultWriter.persist(any(), eq(solution), eq(7L)))
                .thenReturn(new ScheduleResultWriter.WriteOutcome(2, 1));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.SOLVED, outcome.status());
        assertEquals(0L, outcome.scoreHard());
        assertEquals(-10L, outcome.scoreSoft());
        assertEquals("0hard/-10soft", outcome.metrics().get("bestScore"));
        assertEquals(2, outcome.metrics().get("persistedTaskCount"));
        assertEquals(1, outcome.metrics().get("justificationCount"));
    }

    @Test
    void softWeightsShouldBeInjectedWhenStrategyHasEightSoftRules() {
        UUID strategyId = UUID.randomUUID();
        when(strategyService.get(strategyId)).thenReturn(strategy(strategyId));
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true);

        SolveConfig config = service.toConfig(
                new SolveRequest("T-1", SANDBOX_ID, strategyId, "timefold",
                        List.of("SO-1"), Map.of()),
                SolverEngine.TIMEFOLD);

        @SuppressWarnings("unchecked")
        Map<String, Integer> weights = (Map<String, Integer>) config.parameters()
                .get(ScheduleService.SOFT_WEIGHTS_KEY);
        assertNotNull(weights, "生效策略的软规则权重应注入 SolveConfig.parameters");
        assertEquals(8, weights.size());
        assertEquals(100, weights.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(40, weights.get("MIN_TARDINESS"));
        assertFalse(weights.containsKey("HARD_LOCK"));
    }

    @Test
    void noEffectiveStrategyShouldKeepWeightsAbsent() {
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true);

        SolveConfig config = service.toConfig(request(), SolverEngine.TIMEFOLD);

        assertFalse(config.parameters().containsKey(ScheduleService.SOFT_WEIGHTS_KEY),
                "无生效策略时不应注入软约束权重");
    }

    private static StrategyView strategy(UUID strategyId) {
        String[] codes = {"MIN_TARDINESS", "EDD", "SPT", "SAME_PRODUCT_CONTINUOUS",
                "MIN_SETUP_TIME", "BALANCE_LOAD", "PRIORITY_RESPECT", "MIN_MAKESPAN"};
        int[] weightValues = {40, 15, 10, 10, 10, 10, 5, 0};
        List<StrategyRuleView> rules = new ArrayList<>();
        for (int index = 0; index < codes.length; index++) {
            rules.add(new StrategyRuleView(codes[index], codes[index], "OPTIMIZATION", "SOFT",
                    BigDecimal.valueOf(weightValues[index]), Map.of(), true, index + 1));
        }
        rules.add(new StrategyRuleView("HARD_LOCK", "锁定", "INITIAL", "HARD",
                BigDecimal.ZERO, Map.of(), true, 9));
        return new StrategyView(strategyId, "策略A", "FORWARD", 1, Map.of(), Map.of(),
                null, null, Map.of(), rules, rules.size(), BigDecimal.valueOf(100), 1, null, null);
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static SolveRequest request() {
        return new SolveRequest("T-1", SANDBOX_ID, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled() {
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), SANDBOX_ID, null,
                null, "FORWARD", List.of(), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, SANDBOX_ID, problem, List.of());
    }

    private static SolveMetrics metrics() {
        return new SolveMetrics(10L, 5L, 1, "TIME_LIMIT", List.of(), 0, Map.of());
    }

    /**
     * 直接抛异常的引擎替身。
     */
    private static final class ThrowingAdapter implements SolverAdapter {

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            throw new IllegalStateException("timefold crashed");
        }
    }
}
