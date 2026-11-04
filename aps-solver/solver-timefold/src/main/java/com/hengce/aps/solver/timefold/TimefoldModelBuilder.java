package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.score.HardSoftScore;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.calendar.ResourceAvailability;
import com.hengce.aps.solver.timefold.calendar.TimeWindow;
import com.hengce.aps.solver.timefold.dependency.DependencyGraph;
import com.hengce.aps.solver.timefold.dependency.DependencyGraphBuilder;
import com.hengce.aps.solver.timefold.duration.DurationCalculator;
import com.hengce.aps.solver.timefold.duration.TaskDuration;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 中立 {@link ScheduleProblem} 与 Timefold {@link SchedulePlan} 之间的转换。
 *
 * <p>C12 落地：可用时段（§17.1）、工序依赖（§17.2，SF/负滞后/环直接拒绝）、
 * 换型矩阵（§17.3，未命中回退静态换型）、辅助资源占用（§17.4）。
 * 三项降级标记写入计划解并随结果进入 {@code SolveMetrics.extra}。</p>
 */
public final class TimefoldModelBuilder {

    /**
     * 开始时间候选的粒度（小时）。
     */
    private static final int START_TIME_STEP_HOURS = 1;

    /**
     * 开始时间候选覆盖的 horizon（小时，7 天）。
     */
    private static final int HORIZON_HOURS = 168;

    private TimefoldModelBuilder() {
    }

    /**
     * 构建 Timefold 计划解。
     *
     * @param problem 中立排产问题
     * @return 计划解
     */
    public static SchedulePlan build(ScheduleProblem problem) {
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : problem.resources()) {
            if (resource.resourceId() == null) {
                throw new IllegalArgumentException("资源缺少 resourceId: " + resource.code());
            }
            resourceById.put(resource.resourceId(), resource);
        }
        if (resourceById.isEmpty()) {
            throw new IllegalArgumentException("排产问题缺少资源");
        }

        Map<UUID, List<TimeWindow>> windowsByResource = buildAvailabilityWindows(resourceById);
        boolean calendarFallback = resourceById.values().stream()
                .anyMatch(resource -> resource.availabilityWindows().isEmpty());

        Map<UUID, ResourcePlan> resourcePlans = new LinkedHashMap<>();
        for (SchedulingResource resource : resourceById.values()) {
            resourcePlans.put(resource.resourceId(), new ResourcePlan(resource));
        }

        Map<String, TaskAssignment> frozenByKey = new LinkedHashMap<>();
        for (TaskAssignment frozen : problem.frozenAssignments()) {
            frozenByKey.put(taskKey(frozen.orderId(), frozen.operationIndex()), frozen);
        }

        List<TaskPlan> tasks = new ArrayList<>();
        for (SchedulingOrder order : problem.orders()) {
            List<SchedulingOperation> operations = order.operations().stream()
                    .sorted(Comparator.comparingInt(SchedulingOperation::operationIndex))
                    .toList();
            for (SchedulingOperation operation : operations) {
                TaskAssignment frozen = frozenByKey.get(taskKey(order.orderId(), operation.operationIndex()));
                SchedulingResource resource = resolveResource(operation, resourceById, frozen);
                TaskDuration duration = DurationCalculator.calculate(resource, operation, order.qty());
                TaskPlan task = createTask(order, operation, duration, frozen);
                task.setSetupMatrixEntries(problem.setupMatrix());
                task.setAvailabilityRequirements(availabilityRequirements(
                        operation, resource, resourceById, windowsByResource));
                tasks.add(task);
                placeFrozenTask(task, frozen, resourcePlans);
            }
        }

        DependencyGraph dependencyGraph = DependencyGraphBuilder.build(problem.orders());
        List<LocalDateTime> startTimes = buildStartTimeRange(tasks);
        SchedulePlan plan = new SchedulePlan(
                startTimes, new ArrayList<>(resourcePlans.values()), tasks, dependencyGraph.edges());
        plan.setCalendarFallback(calendarFallback);
        plan.setDependencyFallback(dependencyGraph.fallback());
        plan.setSetupMatrixFallback(problem.setupMatrix().isEmpty());
        return plan;
    }

    /**
     * 将 Timefold 最优解映射为中立结果。
     *
     * <p><b>状态口径</b>：只有“所有任务均已排位且 hard 分为 0”才是 {@code SOLVED}；
     * 存在未排位任务且已耗尽时限时返回 {@code TIMEOUT}（构造启发式未完成，最优解只是
     * 部分解/初始解，必须丢弃、不得落库）；其余不可行情况返回 {@code INFEASIBLE}。</p>
     *
     * @param best 最优解
     * @param elapsedMs 求解耗时（毫秒）
     * @param justifications 约束归因
     * @param terminationReason 终止原因（TIME_LIMIT / TERMINATED）
     * @param timeLimitReached 是否因到达时限而终止
     * @return 中立结果
     */
    public static ScheduleSolution toSolution(
            SchedulePlan best,
            long elapsedMs,
            List<ConstraintJustification> justifications,
            String terminationReason,
            boolean timeLimitReached) {
        return toSolution(best, elapsedMs, justifications, terminationReason, timeLimitReached,
                Map.of());
    }

    /**
     * 同上，并合并引擎级指标（例如移动线程的“请求值/生效值/是否被忽略”）。
     *
     * @param best 最优解
     * @param elapsedMs 求解耗时（毫秒）
     * @param justifications 约束归因
     * @param terminationReason 终止原因（TIME_LIMIT / TERMINATED）
     * @param timeLimitReached 是否因到达时限而终止
     * @param solverExtra 引擎级指标片段
     * @return 中立结果
     */
    public static ScheduleSolution toSolution(
            SchedulePlan best,
            long elapsedMs,
            List<ConstraintJustification> justifications,
            String terminationReason,
            boolean timeLimitReached,
            Map<String, Object> solverExtra) {
        List<TaskAssignment> assignments = new ArrayList<>();
        for (ResourcePlan resource : best.getResources()) {
            List<TaskPlan> orderedTasks = new ArrayList<>(resource.getTasks());
            orderedTasks.sort(Comparator.comparing(
                    TaskPlan::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            for (int index = 0; index < orderedTasks.size(); index++) {
                TaskPlan task = orderedTasks.get(index);
                if (task.getStartTime() != null) {
                    assignments.add(toAssignment(resource, task, index));
                }
            }
        }
        HardSoftScore score = best.getScore() == null ? HardSoftScore.ZERO : best.getScore();
        int unassignedTaskCount = (int) best.getTasks().stream()
                .filter(task -> task.getResourceId() == null || task.getStartTime() == null)
                .count();
        boolean usable = unassignedTaskCount == 0 && score.isFeasible();
        String status;
        if (usable) {
            // 时限到达但结果可用（覆盖完整 + hard=0）时仍判 SOLVED，不因“超时”抹掉合法结果。
            status = "SOLVED";
        } else if (timeLimitReached) {
            // 时限到达且结果不可用：一律 TIMEOUT，不得用 ENGINE_ERROR 兜时间上限。
            status = "TIMEOUT";
        } else {
            status = "INFEASIBLE";
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", "timefold");
        extra.put("taskCount", best.getTasks().size());
        extra.put("assignedTaskCount", assignments.size());
        extra.put("unassignedTaskCount", unassignedTaskCount);
        extra.put("discardedBestSolution", status.equals("TIMEOUT"));
        extra.put("calendarFallback", best.isCalendarFallback());
        extra.put("dependencyFallback", best.isDependencyFallback());
        extra.put("setupMatrixFallback", best.isSetupMatrixFallback());
        extra.putAll(solverExtra);
        return new ScheduleSolution(
                status,
                new ScoreFact(
                        BigDecimal.valueOf(score.hardScore()),
                        BigDecimal.valueOf(score.softScore()),
                        score.toString()),
                assignments,
                justifications,
                new SolveMetrics(
                        elapsedMs,
                        0L,
                        0,
                        terminationReason,
                        List.of(),
                        assignments.size(),
                        extra),
                message(score, best, status, unassignedTaskCount));
    }

    private static Map<UUID, List<TimeWindow>> buildAvailabilityWindows(
            Map<UUID, SchedulingResource> resourceById) {
        Map<UUID, List<TimeWindow>> windowsByResource = new LinkedHashMap<>();
        for (SchedulingResource resource : resourceById.values()) {
            List<TimeWindow> windows = new ArrayList<>();
            for (AvailabilityWindow window : resource.availabilityWindows()) {
                LocalDateTime start = toLocal(window.start());
                LocalDateTime end = toLocal(window.end());
                if (!windows.isEmpty() && !start.isAfter(windows.get(windows.size() - 1).start())) {
                    throw new IllegalArgumentException(
                            "资源可用时段必须按 start 升序且不重叠: " + resource.code());
                }
                if (!windows.isEmpty() && start.isBefore(windows.get(windows.size() - 1).end())) {
                    throw new IllegalArgumentException(
                            "资源可用时段必须按 start 升序且不重叠: " + resource.code());
                }
                windows.add(new TimeWindow(start, end));
            }
            windowsByResource.put(resource.resourceId(), List.copyOf(windows));
        }
        return windowsByResource;
    }

    private static List<ResourceAvailability> availabilityRequirements(
            SchedulingOperation operation,
            SchedulingResource primaryResource,
            Map<UUID, SchedulingResource> resourceById,
            Map<UUID, List<TimeWindow>> windowsByResource) {
        List<ResourceAvailability> requirements = new ArrayList<>();
        addRequirement(requirements, primaryResource, windowsByResource);
        for (UUID secondaryResourceId : operation.secondaryResourceIds()) {
            SchedulingResource secondaryResource = resourceById.get(secondaryResourceId);
            if (secondaryResource == null) {
                throw new IllegalArgumentException("工序的辅助资源不存在: "
                        + operation.operationCode() + " / " + secondaryResourceId);
            }
            addRequirement(requirements, secondaryResource, windowsByResource);
        }
        return List.copyOf(requirements);
    }

    private static void addRequirement(
            List<ResourceAvailability> requirements,
            SchedulingResource resource,
            Map<UUID, List<TimeWindow>> windowsByResource) {
        List<TimeWindow> windows = windowsByResource.get(resource.resourceId());
        if (windows == null || windows.isEmpty()) {
            return;
        }
        requirements.add(new ResourceAvailability(resource.resourceId(), resource.code(), windows));
    }

    private static TaskPlan createTask(
            SchedulingOrder order,
            SchedulingOperation operation,
            TaskDuration duration,
            TaskAssignment existing) {
        boolean frozen = isFrozenLevel(existing);
        boolean pinned = existing != null && existing.frozen()
                && existing.startTime() != null && existing.resourceId() != null;
        boolean placeAtExisting = frozen || pinned;
        TaskPlan task = new TaskPlan(
                order.orderId(),
                order.orderNo(),
                operation.operationIndex(),
                operation.operationName(),
                operation.operationTemplateId(),
                operation.primaryResourceId(),
                operation.secondaryResourceIds(),
                order.qty(),
                duration.setupSeconds(),
                duration.runSeconds(),
                frozen,
                placeAtExisting && existing != null ? existing.resourceId() : null,
                placeAtExisting && existing != null ? frozenStartTime(existing) : null);
        task.setPinned(pinned);
        if (existing != null) {
            task.setLockType(existing.lockType());
            task.setLockedFrom(toLocal(existing.lockedFrom()));
            task.setLockedTo(toLocal(existing.lockedTo()));
            task.setLockedResourceIds(existing.lockedResourceIds());
        }
        task.setOperationCode(operation.operationCode());
        task.setMaterialCode(order.materialCode());
        applySoftAttributes(task, order);
        return task;
    }

    /**
     * FROZEN 层判定（C21：{@code FROZEN > lockType > frozen:boolean}）。
     *
     * <p>只有任务状态为 {@code FROZEN} 或 {@code lockType=FROZEN} 时才把既有分配
     * 作为 pinned 起止/资源；{@code TIME_WINDOW}/{@code RESOURCE} 只写入锁字段，
     * 任务仍然可移动，由 W2 的锁硬约束限制其可行域。</p>
     *
     * @param existing 既有分配，可空
     * @return {@code true} 表示 FROZEN 层
     */
    private static boolean isFrozenLevel(TaskAssignment existing) {
        if (existing == null) {
            return false;
        }
        return "FROZEN".equals(existing.status())
                || "FROZEN".equals(existing.lockType());
    }

    /**
     * 映射软约束所需订单字段到计划任务（第 2 节 W4 对 W2 的 D3 前置）：
     * 交期用于 MIN_TARDINESS / EDD，优先级用于 PRIORITY_RESPECT，
     * 产品族用于 SAME_PRODUCT_CONTINUOUS。
     *
     * <p>缺字段 → 规则静默不生效（不会报错，只会让该规则恒为 0 分）：</p>
     * <pre>
     * | 规则编码               | 依赖字段       | 字段缺失时的行为                     |
     * |------------------------|----------------|--------------------------------------|
     * | MIN_TARDINESS          | dueDate        | 逾期谓词返回 null，该任务不计逾期分   |
     * | PRIORITY_RESPECT       | priority       | 优先级排序谓词失效，不产生该规则得分  |
     * | SAME_PRODUCT_CONTINUOUS| productFamily  | 同族判定失效，换型/连续同族得分不产生  |
     * | MIN_SETUP_TIME         | productFamily  | 换型时间按 0 计，规则静默失效         |
     * | MIN_MAKESPAN           | 无             | 仅依赖任务时间，字段缺失不受影响      |
     * | BALANCE_LOAD           | 无             | 仅依赖资源负载，字段缺失不受影响      |
     * | MIN_SOFT_MAKESPAN      | 无             | 仅依赖任务时间，字段缺失不受影响      |
     * | EDD                    | dueDate        | 交期缺失时按无交期任务处理，排序退化  |
     * </pre>
     *
     * <p>因此 M2 必须先完成 `orders.due_date/priority/product_family` 的采集；
     * 缺失时软约束不会失败、但也不会生效，验收数字会“看起来正常却毫无约束力”。</p>
     *
     * @param task 计划任务
     * @param order 订单快照
     */
    private static void applySoftAttributes(TaskPlan task, SchedulingOrder order) {
        task.setDueDate(order.dueDate());
        task.setPriority(order.priority());
        task.setProductFamily(order.productFamily());
    }

    private static void placeFrozenTask(
            TaskPlan task,
            TaskAssignment frozen,
            Map<UUID, ResourcePlan> resourcePlans) {
        if (frozen == null || task.getPinnedStartTime() == null) {
            return;
        }
        task.setStartTime(task.getPinnedStartTime());
        ResourcePlan resourcePlan = resourcePlans.get(task.getPinnedResourceId());
        if (resourcePlan != null) {
            resourcePlan.getTasks().add(task);
        }
    }

    /**
     * 解析工序的标称资源（C15 §20.3.1.4 冻结口径）。
     *
     * <p>标称资源只用于在建模型时算一次 {@code runSeconds}，资源归属仍由
     * {@code ResourcePlan} 的 list variable 决定：</p>
     * <ol>
     *   <li>固定主资源（{@code FIXED}）→ 该资源；</li>
     *   <li>浮动（{@code FLOAT}）+ {@code RESOURCE} 锁 → {@code lockedResourceIds}
     *       中 {@code resource.code} 最小者；</li>
     *   <li>浮动 + 无资源锁 → 场景内全部启用资源中 {@code resource.code} 最小者
     *       （装配层已按 code 升序，顺序确定）。</li>
     * </ol>
     *
     * <p>三种产能模式都依赖资源（{@code UNIT_PER_HOUR} 用 capacity_value × efficiency、
     * {@code BATCH_SECONDS} 用 capacity_value/batch_capacity、{@code SECONDS_PER_UNIT}
     * 也要除以 efficiency），因此候选资源间时长必须等价；该等价性由装配阶段按
     * 同一口径前置校验（不一致返回 42200），本方法只在等价的前提下取标称资源。</p>
     *
     * @param operation 工序快照
     * @param resourceById 场景内资源
     * @param existing 既有分配，可空（提供任务级 {@code RESOURCE} 锁）
     * @return 标称资源
     */
    private static SchedulingResource resolveResource(
            SchedulingOperation operation,
            Map<UUID, SchedulingResource> resourceById,
            TaskAssignment existing) {
        UUID primaryResourceId = operation.primaryResourceId();
        if (primaryResourceId != null) {
            SchedulingResource resource = resourceById.get(primaryResourceId);
            if (resource == null) {
                throw new IllegalArgumentException(
                        "工序的主资源不存在: " + operation.operationCode() + " / " + primaryResourceId);
            }
            return resource;
        }
        if (existing != null && "RESOURCE".equals(existing.lockType())
                && existing.lockedResourceIds() != null && !existing.lockedResourceIds().isEmpty()) {
            List<SchedulingResource> locked = existing.lockedResourceIds().stream()
                    .map(resourceById::get)
                    .filter(resource -> resource != null)
                    .toList();
            if (!locked.isEmpty()) {
                return minByCode(locked);
            }
        }
        return minByCode(new ArrayList<>(resourceById.values()));
    }

    /**
     * 按 {@code resource.code} 字典序取最小资源。
     *
     * @param resources 候选资源
     * @return 标称资源
     */
    private static SchedulingResource minByCode(List<SchedulingResource> resources) {
        return resources.stream()
                .min(Comparator.comparing(SchedulingResource::code,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalArgumentException("排产问题缺少可用资源"));
    }

    private static List<LocalDateTime> buildStartTimeRange(List<TaskPlan> tasks) {
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        List<LocalDateTime> startTimes = new ArrayList<>();
        for (int hour = 0; hour <= HORIZON_HOURS; hour += START_TIME_STEP_HOURS) {
            startTimes.add(base.plusHours(hour));
        }
        for (TaskPlan task : tasks) {
            LocalDateTime pinned = task.getPinnedStartTime();
            if (pinned != null && !startTimes.contains(pinned)) {
                startTimes.add(pinned);
            }
        }
        startTimes.sort(Comparator.naturalOrder());
        return startTimes;
    }

    private static TaskAssignment toAssignment(ResourcePlan resource, TaskPlan task, int index) {
        LocalDateTime setupStart = task.getStartTime();
        LocalDateTime setupEnd = task.getProcessingStartTime();
        LocalDateTime end = task.getEndTime();
        return new TaskAssignment(
                null,
                task.getOrderId(),
                task.getOrderNo(),
                task.getOperationIndex(),
                task.getOperationName(),
                task.getOperationTemplateId(),
                resource.getResourceId(),
                resource.getCode(),
                task.getSecondaryResourceIds(),
                toOffset(setupStart),
                toOffset(setupEnd),
                toOffset(setupEnd),
                toOffset(end),
                task.getQty(),
                index,
                task.isFrozen() ? "FROZEN" : "PLANNED",
                task.isFrozen(),
                null,
                task.getLockType(),
                toOffset(task.getLockedFrom()),
                toOffset(task.getLockedTo()),
                task.getLockedResourceIds());
    }

    private static String message(
            HardSoftScore score, SchedulePlan plan, String status, int unassignedTaskCount) {
        StringBuilder message = new StringBuilder("Timefold 求解完成: ").append(score);
        if ("TIMEOUT".equals(status)) {
            message.append("；到达时限仍存在 ").append(unassignedTaskCount)
                    .append(" 个未排位任务，不发可行解、丢弃最优部分解、不落库");
        } else if (unassignedTaskCount > 0) {
            message.append("；存在 ").append(unassignedTaskCount).append(" 个未排位任务");
        }
        List<String> fallbacks = new ArrayList<>();
        if (plan.isCalendarFallback()) {
            fallbacks.add("资源可用时段缺失，按 7×24 计算");
        }
        if (plan.isDependencyFallback()) {
            fallbacks.add("工序依赖缺失，按 operationIndex 串行补 FS/0");
        }
        if (plan.isSetupMatrixFallback()) {
            fallbacks.add("换型矩阵为空，回退静态 setup_seconds");
        }
        if (!fallbacks.isEmpty()) {
            message.append("；降级: ").append(String.join("；", fallbacks));
        }
        return message.toString();
    }

    private static LocalDateTime frozenStartTime(TaskAssignment frozen) {
        if (frozen.setupStartTime() != null) {
            return toLocal(frozen.setupStartTime());
        }
        if (frozen.startTime() != null) {
            return toLocal(frozen.startTime());
        }
        return null;
    }

    private static LocalDateTime toLocal(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    private static String taskKey(UUID orderId, int operationIndex) {
        return String.valueOf(orderId) + ':' + operationIndex;
    }
}
