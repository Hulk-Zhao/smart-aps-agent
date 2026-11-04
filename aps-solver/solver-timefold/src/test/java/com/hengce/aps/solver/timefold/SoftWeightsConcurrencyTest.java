package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 软约束权重的并发隔离取证（真跑 Timefold，不用 mock）。
 *
 * <p>{@link SoftConstraintWeights} 是进程级静态注册表（{@code AtomicReference}），本身没有请求作用域；
 * 隔离依赖 {@link TimefoldSolverAdapter} 的进程级静态锁 {@code SOLVER_LOCK}，其临界区覆盖
 * {@code activate → SolverFactory.create → buildSolver → solve → disable} 全段。
 * 本用例用两个<b>同时</b>发出的求解请求（各自独立的适配器实例、不同权重）验证：</p>
 * <ul>
 *   <li>两个同时发出的请求分别等于各自单跑应得的分数（短题面，分数确定）；</li>
 *   <li>先发起 3 秒长求解、200ms 后再发一个不带权重的快求解时，后者被静态锁挡住约 2.8 秒，
 *       且 {@code softScore==0}（没有沾到对方的权重）；</li>
 *   <li>争用时后到方拿到明确的 {@code FAILED} + “求解器正被其他求解任务占用”，而不是静默算歪；</li>
 *   <li>已建成的求解会话在注册表被清空后仍按建厂时的权重计分（权重只在建厂时读取）。</li>
 * </ul>
 *
 * <p>确定分题面（{@link #pinnedProblem()}）：单个冻结任务固定在 2026-01-05 08:00、加工 30 分钟，
 * 订单交期 2026-01-04，超期恒为 510 分钟、makespan 恒为 30 分钟：
 * 权重 0 → {@code -480 = -(16 * 30)}；权重 40 → {@code -20580 = -(40 * 510 + 6 * 30)}。</p>
 */
class SoftWeightsConcurrencyTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005a1");

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000005b1");

    private static final LocalDateTime PINNED_START = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 1, 4);

    private static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    private static final long BASELINE_SOFT_SCORE = -480L;

    private static final long WEIGHTED_SOFT_SCORE = -20580L;

    /**
     * 串行化判据：后到方被挡在临界区外的时间必须不低于 2 秒（持锁方跑满 3 秒，后到方在持锁方进入临界区后发起）。
     */
    private static final long BLOCKED_MIN_MILLIS = 2000L;

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    /**
     * 两个同时发出的请求、权重不同：各自得分必须与单跑一致。
     */
    @Test
    void concurrentRequestsWithDifferentWeightsMustNotShareState() throws Exception {
        long expectedBaseline = singleRunSoftScore(weights(0));
        long expectedWeighted = singleRunSoftScore(weights(40));

        Run[] runs = runBoth(pinnedProblem(), "baseline", fastConfig(Map.of("softWeights", weights(0))),
                "weighted", fastConfig(Map.of("softWeights", weights(40))));
        Run baseline = runs[0];
        Run weighted = runs[1];

        assertEquals("SOLVED", baseline.solution().status());
        assertEquals("SOLVED", weighted.solution().status());
        assertEquals(expectedBaseline, baseline.softScore().longValueExact(),
                "权重 0 的并发请求被另一套权重污染: " + describe(baseline, weighted));
        assertEquals(expectedWeighted, weighted.softScore().longValueExact(),
                "权重 40 的并发请求被另一套权重污染: " + describe(baseline, weighted));
        assertEquals(0L, baseline.hardScore().longValueExact());
        assertEquals(0L, weighted.hardScore().longValueExact());
        assertFalse(SoftConstraintWeights.isEnabled(), "并发结束后注册表必须为空");
    }

    /**
     * 一个请求带权重、另一个请求不带权重同时发出：不带权重的必须仍是 M1 仅硬约束（{@code softScore=0}）。
     */
    @Test
    void concurrentWeightedAndOmittedRequestsMustNotLeakResidue() throws Exception {
        long expectedWeighted = singleRunSoftScore(weights(40));

        Run[] runs = runBoth(pinnedProblem(), "weighted", fastConfig(Map.of("softWeights", weights(40))),
                "omitted", fastConfig(Map.of()));
        Run weighted = runs[0];
        Run omitted = runs[1];

        assertEquals("SOLVED", weighted.solution().status());
        assertEquals("SOLVED", omitted.solution().status());
        assertEquals(expectedWeighted, weighted.softScore().longValueExact());
        assertEquals(0L, omitted.softScore().longValueExact(),
                "未带 softWeights 的并发请求拿到了别人的软得分，说明注册表跨请求泄漏: "
                        + describe(weighted, omitted));
        assertFalse(SoftConstraintWeights.isEnabled(), "并发结束后注册表必须为空");
    }

    /**
     * 串行化取证：先发起一个 3 秒的长求解，200ms 后发起一个不带宽度的快求解。
     * 若静态锁生效，快求解会被挡在临界区外约 2.8 秒，且拿不到对方的权重（{@code softScore==0}）；
     * 长求解按自己的权重产生负软得分。
     */
    @Test
    void longSolveMustBlockSecondRequestAndKeepResidueOut() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> longRun = pool.submit(
                    () -> solveInThread("weighted", busyProblem(), timedConfig(Map.of("softWeights", weights(40)), 3),
                            started));
            started.countDown();
            awaitWeightsActivated();
            Run probe = solveInThread("omitted-probe", busyProblem(), fastConfig(Map.of()), new CountDownLatch(0));
            Run weighted = longRun.get(60, TimeUnit.SECONDS);

            assertEquals("SOLVED", weighted.solution().status(),
                    "长求解失败: " + weighted.solution().message());
            assertTrue(weighted.softScore().longValueExact() < 0,
                    "带权重的长求解必须产生负软得分（交期早于排产起点，逾期不可避免）: "
                            + describe(weighted, probe));
            assertEquals("SOLVED", probe.solution().status(),
                    "快求解失败: " + probe.solution().message());
            assertEquals(0L, probe.softScore().longValueExact(),
                    "不带宽度的快求解在长求解进行中拿到了软得分，说明注册表跨请求泄漏: "
                            + describe(weighted, probe));
            assertTrue(probe.durationMillis() >= BLOCKED_MIN_MILLIS,
                    "快求解只用了 " + probe.durationMillis() + "ms 就返回，说明没有被静态锁挡住: "
                            + describe(weighted, probe));
        }
        assertFalse(SoftConstraintWeights.isEnabled(), "并发结束后注册表必须为空");
    }

    /**
     * 争用取证：持锁方 4 秒、后到方等待上限 2 秒，后到方必须拿到明确的 {@code FAILED} 与“求解器正被
     * 其他求解任务占用”说明，而不是静默用着别人的权重返回 {@code SOLVED}。
     */
    @Test
    void contendedRequestMustFailExplicitlyInsteadOfStealingWeights() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> holder = pool.submit(
                    () -> solveInThread("weighted-holder", busyProblem(), timedConfig(Map.of("softWeights", weights(40)), 4),
                            started));
            started.countDown();
            awaitWeightsActivated();
            Run rejected = solveInThread("omitted-rejected", busyProblem(),
                    timedConfig(Map.of(), 2), new CountDownLatch(0));
            Run holderRun = holder.get(60, TimeUnit.SECONDS);

            assertEquals("FAILED", rejected.solution().status(),
                    "后到方应明确失败而不是静默求解: " + rejected.solution().message());
            assertTrue(rejected.solution().message().contains("求解器正被其他求解任务占用"),
                    "失败原因必须指向锁争用，实际: " + rejected.solution().message());
            assertEquals("SOLVED", holderRun.solution().status(),
                    "持锁方必须正常完成: " + holderRun.solution().message());
            assertTrue(holderRun.softScore().longValueExact() < 0,
                    "持锁方必须按自己的权重计分: " + describe(holderRun, rejected));
        }
        assertFalse(SoftConstraintWeights.isEnabled(), "并发结束后注册表必须为空");
    }

    /**
     * 机制证据：权重在 {@code buildSolver()}（即 {@code defineConstraints}）时固化进约束流，
     * 之后改动注册表不会影响已建成的求解会话。因此“并发不串”的边界是“同一时刻只有一个会话
     * 处在 activate→solve 区间”，由适配器的静态锁保证。
     */
    @Test
    void solverSessionMustFreezeWeightsAtBuildTime() {
        SoftConstraintWeights.activate(weights(40));
        SolverFactory<SchedulePlan> factory = createFactory();
        Solver<SchedulePlan> solver = factory.buildSolver();
        SoftConstraintWeights.disable();

        SchedulePlan plan = TimefoldModelBuilder.build(pinnedProblem());
        SchedulePlan best = solver.solve(plan);
        ScheduleSolution solution = TimefoldModelBuilder.toSolution(
                best, 0L, ConstraintJustifications.from(best), "TERMINATED", false);

        assertEquals(-20580L, solution.score().softScore().longValueExact(),
                "注册表在求解前被清空，但已建成的会话仍应按建厂时的权重计分");
        assertFalse(SoftConstraintWeights.isEnabled());
    }

    /**
     * 先单跑一遍取期望值：串行执行，注册表在调用前后都为空。
     */
    private static long singleRunSoftScore(Map<String, Integer> weights) {
        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(pinnedProblem(), fastConfig(Map.of("softWeights", weights)));
        assertEquals("SOLVED", solution.status());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertFalse(SoftConstraintWeights.isEnabled(), "单跑结束必须回到关闭状态");
        long score = solution.score().softScore().longValueExact();
        assertEquals(weights.get("MIN_TARDINESS") == 0 ? BASELINE_SOFT_SCORE : WEIGHTED_SOFT_SCORE, score);
        return score;
    }

    /**
     * 两个线程在同一个闸门后同时起跑：同一份题面，只有权重（或有无权重）不同。
     * 每个线程都用独立的 {@link TimefoldSolverAdapter} 实例，因此能命中同一把静态锁。
     */
    private static Run[] runBoth(ScheduleProblem problem, String firstLabel, SolveConfig firstConfig,
            String secondLabel, SolveConfig secondConfig) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> first = pool.submit(
                    () -> solveInThread(firstLabel, problem, firstConfig, gate));
            Future<Run> second = pool.submit(
                    () -> solveInThread(secondLabel, problem, secondConfig, gate));
            gate.countDown();
            return new Run[] {first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)};
        }
    }

    /**
     * 等持锁方真正进入临界区（注册表已激活）再发起后到请求，避免线程调度导致起点反序。
     */
    private static void awaitWeightsActivated() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!SoftConstraintWeights.isEnabled()) {
            if (System.nanoTime() > deadlineNanos) {
                fail("持锁方 10 秒内未进入临界区，注册表始终未激活");
            }
            Thread.sleep(5);
        }
    }

    /**
     * 单线程求解，闸门放行后才真正调用适配器，保证两个请求的起点尽量接近。
     */
    private static Run solveInThread(String label, ScheduleProblem problem, SolveConfig config,
            CountDownLatch gate) throws InterruptedException {
        gate.await();
        long beginNanos = System.nanoTime();
        ScheduleSolution solution = new TimefoldSolverAdapter().solve(problem, config);
        return new Run(label, solution, beginNanos, System.nanoTime());
    }

    private static String describe(Run first, Run second) {
        return "[" + first.label() + " " + first.durationMillis() + "ms status=" + first.solution().status()
                + " soft=" + first.softScore() + "] vs [" + second.label() + " " + second.durationMillis()
                + "ms status=" + second.solution().status() + " soft=" + second.softScore() + "]";
    }

    private static SolverFactory<SchedulePlan> createFactory() {
        TerminationConfig termination = new TerminationConfig().withSpentLimit(Duration.ofSeconds(1));
        termination.setStepCountLimit(10);
        SolverConfig config = new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(1L)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    /**
     * 快跑配置：超时 5 秒、步数上限 10，用于确定分题面的单跑与并发对比。
     */
    private static SolveConfig fastConfig(Map<String, Object> parameters) {
        return new SolveConfig("timefold", 5, 1, "LATE_ACCEPTANCE", 1L, 10L, 0,
                "FORWARD", true, parameters);
    }

    /**
     * 长跑配置：给定超时、不限步数，用于串行化取证。
     */
    private static SolveConfig timedConfig(Map<String, Object> parameters, int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, parameters);
    }

    /**
     * 权重：{@code MIN_TARDINESS=0} 一套；{@code MIN_TARDINESS=40} 一套（其余四条各下调 10 补齐），
     * 两套合计均为 100。
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

    /**
     * 确定分题面：单个冻结任务固定在一个时间点，分数与搜索过程无关。
     */
    private static ScheduleProblem pinnedProblem() {
        SchedulingResource resource = new SchedulingResource(
                RESOURCE_ID, "R-CONC", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
        SchedulingOperation operation = new SchedulingOperation(
                10, "OP-10", "工序 10", null, BigDecimal.valueOf(1800), BigDecimal.ZERO,
                RESOURCE_ID, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(
                ORDER_ID, "SO-CONC", "MAT-CONC", "FAM-CONC", BigDecimal.ONE,
                DUE_DATE, "NORMAL", "FROZEN", UUID.randomUUID(), List.of(operation));
        OffsetDateTime start = PINNED_START.atOffset(ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(
                null, ORDER_ID, "SO-CONC", 10, "工序 10", null,
                RESOURCE_ID, "R-CONC", List.of(),
                start, start, start, start.plusSeconds(1800),
                BigDecimal.ONE, 0, "FROZEN", true, 1);
        return new ScheduleProblem(
                "tenant-conc", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of());
    }

    /**
     * 长跑题面：3 个订单、每个 1 道工序（未冻结），全部只能排在同一台主资源上。
     * 有可移动的开工时间，3 秒超时会被用满（这正是串行化取证需要的持锁时长）；工序间无依赖，保证可行。
     */
    private static ScheduleProblem busyProblem() {
        List<SchedulingResource> resources = List.of(
                new SchedulingResource(RESOURCE_ID, "R-CONC-1", "PRIMARY", null, "SECONDS_PER_UNIT",
                        BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE"));
        List<SchedulingOrder> orders = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000006b" + index);
            List<SchedulingOperation> operations = List.of(
                    new SchedulingOperation(10, "OP-10", "工序 10", null,
                            BigDecimal.valueOf(1800), BigDecimal.ZERO, RESOURCE_ID, null, List.of(), false));
            orders.add(new SchedulingOrder(orderId, "SO-CONC-" + index, "MAT-CONC-" + index, "FAM-CONC",
                    BigDecimal.ONE, DUE_DATE, "NORMAL", "UNFROZEN", UUID.randomUUID(), operations));
        }
        return new ScheduleProblem(
                "tenant-conc", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                orders, resources, List.of(), List.of());
    }

    /**
     * 一次求解的观测记录：标签、结果、起止纳秒。
     */
    private record Run(String label, ScheduleSolution solution, long beginNanos, long endNanos) {

        BigDecimal softScore() {
            return solution.score() == null ? null : solution.score().softScore();
        }

        BigDecimal hardScore() {
            return solution.score() == null ? null : solution.score().hardScore();
        }

        long durationMillis() {
            return (endNanos - beginNanos) / 1_000_000L;
        }
    }
}
