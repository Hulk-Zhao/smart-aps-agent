package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustmentRow;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.service.masterdata.order.FreezeLock;
import com.hengce.aps.service.masterdata.order.OrderView;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 甘特人工调整服务（C22）。
 *
 * <p>调整语义：</p>
 * <ul>
 *   <li>{@code MOVE}：平移加工窗口（保持时长），可换主资源；{@code FROZEN} 任务拒绝；</li>
 *   <li>{@code LOCK}：写 {@code lock_type/locked_from/locked_to/locked_resource_ids}（V5）；</li>
 *   <li>{@code FREEZE}/{@code UNFREEZE}：切 {@code status} 与锁类型；{@code FREEZE} 必须带 reason；</li>
 *   <li>{@code SPLIT}/{@code MERGE}：M2 枚举冻结，返回 50100。</li>
 * </ul>
 *
 * <p>两层冻结取交集（C21 §24.3）：订单级 {@code freezeLock} 与任务级锁都是硬约束，
 * 任务级只能更窄；空交集在请求阶段直接 42201，message 同时列出订单约束与任务约束。
 * 版本过期 40901；已执行任务（IN_PROGRESS/COMPLETED）拒绝调整 40903。</p>
 *
 * <p>{@code dryRun=true} 只做完整校验与“将要写入”的视图计算，不写 {@code task} 与
 * {@code task_adjustment}。</p>
 */
@Service
public class TaskAdjustService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String LOCK_NONE = "NONE";

    private static final String LOCK_TIME_WINDOW = "TIME_WINDOW";

    private static final String LOCK_RESOURCE = "RESOURCE";

    private static final String LOCK_FROZEN = "FROZEN";

    private static final String STATUS_PLANNED = "PLANNED";

    private static final String STATUS_FROZEN = "FROZEN";

    private static final String ORDER_FROZEN = "FROZEN";

    private static final String ORDER_LOCKED_WINDOW = "LOCKED_TIME_WINDOW";

    private static final String ORDER_LOCKED_RESOURCE = "LOCKED_RESOURCE";

    private static final int MAX_PAGE_SIZE = 100;

    private final TaskAdjustmentRepository adjustmentRepository;

    private final ScheduleRepository scheduleRepository;

    private final CalendarMapper calendarMapper;

    private final ScheduleProblemAssembler assembler;

    /**
     * 构造服务。
     *
     * @param adjustmentRepository 调整持久层
     * @param scheduleRepository 排产持久层（资源编码映射）
     * @param calendarMapper 日历 Mapper（可用时段校验）
     * @param assembler 问题装配器，复用其日历窗口展开逻辑
     */
    public TaskAdjustService(TaskAdjustmentRepository adjustmentRepository,
                             ScheduleRepository scheduleRepository,
                             CalendarMapper calendarMapper,
                             ScheduleProblemAssembler assembler) {
        this.adjustmentRepository = adjustmentRepository;
        this.scheduleRepository = scheduleRepository;
        this.calendarMapper = calendarMapper;
        this.assembler = assembler;
    }

    /**
     * 人工调整任务。
     *
     * @param taskId 任务主键
     * @param command 调整命令
     * @return 调整结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult adjust(long taskId, TaskAdjustCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String operation = normalizeOperation(command.getOperation());
        if ("SPLIT".equals(operation) || "MERGE".equals(operation)) {
            throw new BizException(ErrorCode.NOT_IMPLEMENTED,
                    "M2 冻结 SPLIT/MERGE 枚举，暂未实现");
        }
        if (command.getVersion() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 必填");
        }
        UUID tenantId = TenantContext.getTenantId();
        AdjustTaskRow row = adjustmentRepository.findTask(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
        if (command.getVersion() != row.version()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "任务版本已变化（当前 " + row.version() + "，请求 " + command.getVersion() + "），请刷新后重试");
        }
        rejectExecuted(row);

        PlannedState planned = plan(tenantId, row, operation, command);
        validate(tenantId, row, operation, planned);
        boolean dryRun = Boolean.TRUE.equals(command.getDryRun());
        Map<String, Object> before = snapshot(tenantId, row);
        Map<String, Object> after = snapshot(planned);
        if (dryRun) {
            return new TaskAdjustResult(null, true, true, toView(tenantId, planned, row.version()), List.of());
        }
        int updated = adjustmentRepository.updateTask(tenantId, taskId, row.version(),
                planned.resourceId(), planned.startTime(), planned.endTime(),
                planned.setupStartTime(), planned.setupEndTime(), planned.status(), planned.lockType(),
                planned.lockedFrom(), planned.lockedTo(), toJson(planned.lockedResourceIds()));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        long adjustmentId = adjustmentRepository.insertAdjustment(tenantId, taskId, row.sandboxId(),
                operation, toJson(before), toJson(after), trimToNull(command.getReason()),
                AuditContext.getCurrentUserId());
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, planned, row.version() + 1), List.of());
    }

    /**
     * 撤销最近一次调整（按任务栈）。
     *
     * @param adjustmentId 调整记录主键
     * @return 撤销后的任务视图
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult undo(long adjustmentId) {
        UUID tenantId = TenantContext.getTenantId();
        AdjustmentRow target = requireAdjustment(tenantId, adjustmentId);
        if (target.undone()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整已撤销，无需重复撤销");
        }
        AdjustmentRow latest = adjustmentRepository.findLatestActive(tenantId, target.taskId())
                .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT, "找不到可撤销的调整"));
        if (latest.id() != target.id()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "只能撤销最近一次调整（最近记录 " + latest.id() + "）");
        }
        AdjustTaskRow row = requireTask(tenantId, target.taskId());
        PlannedState restored = fromSnapshot(tenantId, parseJson(target.beforeJson()));
        int updated = adjustmentRepository.updateTask(tenantId, row.taskId(), row.version(),
                restored.resourceId(), restored.startTime(), restored.endTime(),
                restored.setupStartTime(), restored.setupEndTime(), restored.status(), restored.lockType(),
                restored.lockedFrom(), restored.lockedTo(), toJson(restored.lockedResourceIds()));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        adjustmentRepository.markUndone(tenantId, adjustmentId, true);
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, restored, row.version() + 1), List.of());
    }

    /**
     * 重做已撤销的调整。
     *
     * @param adjustmentId 调整记录主键
     * @return 重做后的任务视图
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult redo(long adjustmentId) {
        UUID tenantId = TenantContext.getTenantId();
        AdjustmentRow target = requireAdjustment(tenantId, adjustmentId);
        if (!target.undone()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整未撤销，无需重做");
        }
        if (adjustmentRepository.existsLaterAdjustment(tenantId, target.taskId(), target.id())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整之后已有新调整，不能重做");
        }
        AdjustTaskRow row = requireTask(tenantId, target.taskId());
        PlannedState restored = fromSnapshot(tenantId, parseJson(target.afterJson()));
        int updated = adjustmentRepository.updateTask(tenantId, row.taskId(), row.version(),
                restored.resourceId(), restored.startTime(), restored.endTime(),
                restored.setupStartTime(), restored.setupEndTime(), restored.status(), restored.lockType(),
                restored.lockedFrom(), restored.lockedTo(), toJson(restored.lockedResourceIds()));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        adjustmentRepository.markUndone(tenantId, adjustmentId, false);
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, restored, row.version() + 1), List.of());
    }

    /**
     * 分页查询任务调整历史。
     *
     * @param taskId 任务主键
     * @param page 页码，1 起始
     * @param size 页大小
     * @return 历史分页
     */
    public AdjustmentPage history(long taskId, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int total = adjustmentRepository.countHistory(tenantId, taskId);
        List<TaskAdjustmentView> items = adjustmentRepository
                .findHistory(tenantId, taskId, safeSize, (safePage - 1) * safeSize).stream()
                .map(row -> new TaskAdjustmentView(row.id(), row.adjustType(), parseJson(row.beforeJson()),
                        parseJson(row.afterJson()), row.reason(), row.undone(), row.createdAt()))
                .toList();
        return new AdjustmentPage(items, total, safePage, safeSize);
    }

    private void rejectExecuted(AdjustTaskRow row) {
        if ("IN_PROGRESS".equals(row.status()) || "COMPLETED".equals(row.status())) {
            throw new BizException(ErrorCode.PUBLISHED_CONFLICT,
                    "任务已执行（" + row.status() + "），禁止人工调整");
        }
    }

    private PlannedState plan(UUID tenantId, AdjustTaskRow row, String operation, TaskAdjustCommand command) {
        return switch (operation) {
            case "MOVE" -> planMove(tenantId, row, command);
            case "LOCK" -> planLock(tenantId, row, command);
            case "FREEZE" -> planFreeze(row, command);
            case "UNFREEZE" -> planUnfreeze(row);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 operation: " + operation);
        };
    }

    private PlannedState planMove(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command) {
        if (STATUS_FROZEN.equals(row.status()) || LOCK_FROZEN.equals(row.lockType())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务处于 FROZEN，拒绝 MOVE: taskId=" + row.taskId());
        }
        if (command.getStartTime() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MOVE 必须提供 startTime");
        }
        if (row.startTime() == null || row.endTime() == null) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务缺少当前加工时间，无法按 MOVE 平移: taskId=" + row.taskId());
        }
        Duration duration = Duration.between(row.startTime(), row.endTime());
        OffsetDateTime start = command.getStartTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = start.plus(duration);
        Duration setup = row.setupStartTime() == null || row.setupEndTime() == null
                ? Duration.ZERO : Duration.between(row.setupStartTime(), row.setupEndTime());
        OffsetDateTime setupEnd = start;
        OffsetDateTime setupStart = setup.isZero() ? start : start.minus(setup);
        UUID resourceId = row.resourceId();
        String resourceCode = row.resourceCode();
        if (command.getResourceCode() != null && !command.getResourceCode().isBlank()) {
            TaskAdjustmentRepository.ResourceRef ref = requireResource(tenantId, command.getResourceCode());
            resourceId = ref.resourceId();
            resourceCode = ref.code();
        }
        return new PlannedState(resourceId, resourceCode, setupStart, setupEnd, start, end,
                row.status(), row.lockType(), row.lockedFrom(), row.lockedTo(), lockedIdsOf(row), row.operationIndex());
    }

    private PlannedState planLock(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command) {
        if (STATUS_FROZEN.equals(row.status()) || LOCK_FROZEN.equals(row.lockType())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务处于 FROZEN，LOCK 不得放宽其约束: taskId=" + row.taskId());
        }
        if (command.getLockFrom() == null || command.getLockTo() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LOCK 必须提供 lockFrom/lockTo");
        }
        OffsetDateTime from = command.getLockFrom().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime to = command.getLockTo().withOffsetSameInstant(ZoneOffset.UTC);
        if (!to.isAfter(from)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lockTo 必须晚于 lockFrom");
        }
        List<String> codes = command.getLockedResourceCodes() == null ? List.of()
                : command.getLockedResourceCodes().stream().filter(code -> code != null && !code.isBlank())
                .map(String::trim).distinct().toList();
        List<UUID> ids = new ArrayList<>(codes.size());
        for (String code : codes) {
            ids.add(requireResource(tenantId, code).resourceId());
        }
        String lockType = ids.isEmpty() ? LOCK_TIME_WINDOW : LOCK_RESOURCE;
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(), row.status(), lockType,
                from, to, List.copyOf(ids), row.operationIndex());
    }

    private PlannedState planFreeze(AdjustTaskRow row, TaskAdjustCommand command) {
        if (trimToNull(command.getReason()) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "FREEZE 必须提供 reason");
        }
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(), STATUS_FROZEN, LOCK_FROZEN,
                null, null, List.of(), row.operationIndex());
    }

    private PlannedState planUnfreeze(AdjustTaskRow row) {
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(),
                STATUS_FROZEN.equals(row.status()) ? STATUS_PLANNED : row.status(),
                LOCK_NONE, null, null, List.of(), row.operationIndex());
    }

    /**
     * 可行性校验：两层冻结取交集 + 资源重叠 + 工序依赖 + 日历可用时段。
     *
     * @param tenantId 租户标识
     * @param row 调整前任务
     * @param operation 操作类型
     * @param planned 目标状态
     */
    private void validate(UUID tenantId, AdjustTaskRow row, String operation, PlannedState planned) {
        FreezeLock orderLock = orderLock(row);
        if (ORDER_FROZEN.equals(row.orderFreezeStatus()) && !"FREEZE".equals(operation)) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "订单级已冻结，任务级不可放宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(orderLock));
        }
        if (!"LOCK".equals(operation)) {
            if (ORDER_LOCKED_WINDOW.equals(row.orderFreezeStatus())) {
                requireInsideOrderWindow(row, orderLock, planned.startTime(), planned.endTime());
            }
            if (ORDER_LOCKED_RESOURCE.equals(row.orderFreezeStatus())) {
                requireSubsetOfOrderResources(row, orderLock, planned.resourceCode());
            }
            if (LOCK_TIME_WINDOW.equals(row.lockType())) {
                requireInsideTaskWindow(row, planned.startTime(), planned.endTime());
            }
            if (LOCK_RESOURCE.equals(row.lockType()) && planned.resourceId() != null
                    && !lockedIdsOf(row).contains(planned.resourceId())) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "任务资源锁不允许切换到其它资源: taskId=" + row.taskId()
                                + ", lockType=RESOURCE, lockedResourceIds=" + lockedIdsOf(row));
            }
        } else {
            requireTaskLockWithinOrder(row, orderLock, planned.lockedFrom(), planned.lockedTo(),
                    planned.lockedResourceIds());
        }
        if (!"MOVE".equals(operation)) {
            return;
        }
        if (planned.startTime() == null || planned.endTime() == null
                || !planned.endTime().isAfter(planned.startTime())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "调整后的加工窗口必须满足 endTime > startTime");
        }
        if (planned.resourceId() == null) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "MOVE 需要目标资源");
        }
        List<AdjustTaskRow> overlaps = adjustmentRepository.findOverlappingTasks(tenantId, row.sandboxId(),
                planned.resourceId(), row.taskId(), planned.startTime(), planned.endTime());
        if (!overlaps.isEmpty()) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "目标资源在 " + planned.startTime() + " – " + planned.endTime()
                            + " 已有任务占用: taskIds=" + overlaps.stream().map(AdjustTaskRow::taskId).toList());
        }
        requireDependencyOrder(tenantId, row, planned.startTime(), planned.endTime());
        requireCalendarAvailability(tenantId, planned);
    }

    private void requireInsideOrderWindow(AdjustTaskRow row, FreezeLock lock,
                                          OffsetDateTime start, OffsetDateTime end) {
        if (lock == null || lock.from() == null || lock.to() == null) {
            return;
        }
        if (start == null || end == null || start.isBefore(lock.from()) || end.isAfter(lock.to())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务窗口与订单锁窗无交集（任务级不得比订单级更宽）: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(lock) + ", taskId=" + row.taskId()
                            + ", taskWindow=" + start + " – " + end);
        }
    }

    private void requireSubsetOfOrderResources(AdjustTaskRow row, FreezeLock lock, String resourceCode) {
        if (lock == null || lock.normalizedResourceCodes().isEmpty()) {
            return;
        }
        if (resourceCode == null || !lock.normalizedResourceCodes().contains(resourceCode)) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务资源不在订单锁定资源集合内，任务级不得比订单级更宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(lock) + ", taskId=" + row.taskId()
                            + ", taskResource=" + resourceCode);
        }
    }

    private void requireInsideTaskWindow(AdjustTaskRow row, OffsetDateTime start, OffsetDateTime end) {
        if (row.lockedFrom() == null || row.lockedTo() == null) {
            return;
        }
        if (start == null || end == null || start.isBefore(row.lockedFrom()) || end.isAfter(row.lockedTo())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务窗口超出任务时间锁: taskId=" + row.taskId() + ", lockType=TIME_WINDOW"
                            + ", lockedWindow=" + row.lockedFrom() + " – " + row.lockedTo()
                            + ", taskWindow=" + start + " – " + end);
        }
    }

    private void requireTaskLockWithinOrder(AdjustTaskRow row, FreezeLock orderLock,
                                            OffsetDateTime from, OffsetDateTime to, List<UUID> resourceIds) {
        if (orderLock == null) {
            return;
        }
        if (ORDER_LOCKED_WINDOW.equals(row.orderFreezeStatus()) && orderLock.from() != null
                && orderLock.to() != null
                && (from.isBefore(orderLock.from()) || to.isAfter(orderLock.to()))) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务锁窗超出订单锁窗，任务级不得比订单级更宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(orderLock) + ", taskId=" + row.taskId()
                            + ", taskLockWindow=" + from + " – " + to);
        }
        if (ORDER_LOCKED_RESOURCE.equals(row.orderFreezeStatus())
                && !orderLock.normalizedResourceCodes().isEmpty() && !resourceIds.isEmpty()) {
            Map<UUID, String> codes = scheduleRepository.findResourceCodes(TenantContext.getTenantId());
            String widened = resourceIds.stream().map(codes::get)
                    .filter(code -> code == null || !orderLock.normalizedResourceCodes().contains(code))
                    .findFirst().orElse(null);
            if (widened != null) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "任务锁定资源超出订单锁定资源集合: orderNo=" + row.orderNo()
                                + ", freezeLock=" + describe(orderLock) + ", taskId=" + row.taskId()
                                + ", lockedResourceIds=" + resourceIds);
            }
        }
    }

    private void requireDependencyOrder(UUID tenantId, AdjustTaskRow row,
                                        OffsetDateTime start, OffsetDateTime end) {
        List<AdjustTaskRow> orderTasks = adjustmentRepository.findOrderTasks(
                tenantId, row.sandboxId(), row.orderId());
        List<Long> conflicts = new ArrayList<>();
        for (AdjustTaskRow other : orderTasks) {
            if (other.taskId() == row.taskId()) {
                continue;
            }
            if (other.operationIndex() < row.operationIndex() && other.endTime() != null
                    && other.endTime().isAfter(start)) {
                conflicts.add(other.taskId());
            }
            if (other.operationIndex() > row.operationIndex() && other.startTime() != null
                    && other.startTime().isBefore(end)) {
                conflicts.add(other.taskId());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "调整后违反工序前后依赖: taskId=" + row.taskId() + ", relatedTaskIds=" + conflicts);
        }
    }

    private void requireCalendarAvailability(UUID tenantId, PlannedState planned) {
        UUID calendarId = adjustmentRepository.findResourceByCode(tenantId, planned.resourceCode())
                .map(TaskAdjustmentRepository.ResourceRef::calendarId).orElse(null);
        if (calendarId == null) {
            return;
        }
        Calendar calendar = calendarMapper.findById(calendarId);
        OffsetDateTime from = planned.setupStartTime() == null ? planned.startTime() : planned.setupStartTime();
        if (calendar == null || planned.endTime() == null || from == null) {
            return;
        }
        List<AvailabilityWindow> windows = assembler.expand(calendar, from, planned.endTime());
        if (windows.isEmpty()) {
            return;
        }
        boolean contained = windows.stream().anyMatch(window ->
                !planned.startTime().isBefore(window.start()) && !planned.endTime().isAfter(window.end()));
        if (!contained) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "调整后窗口落在资源日历非可用时段: resource=" + planned.resourceCode()
                            + ", window=" + planned.startTime() + " – " + planned.endTime());
        }
    }

    private AdjustTaskRow requireTask(UUID tenantId, long taskId) {
        return adjustmentRepository.findTask(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
    }

    private AdjustmentRow requireAdjustment(UUID tenantId, long adjustmentId) {
        return adjustmentRepository.findAdjustment(tenantId, adjustmentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "调整记录不存在: " + adjustmentId));
    }

    private TaskAdjustmentRepository.ResourceRef requireResource(UUID tenantId, String code) {
        return adjustmentRepository.findResourceByCode(tenantId, code.trim())
                .orElseThrow(() -> new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "资源不存在: " + code));
    }

    private FreezeLock orderLock(AdjustTaskRow row) {
        if (row.orderExtAttrs() == null || row.orderExtAttrs().isBlank()) {
            return null;
        }
        return OrderView.parseFreezeLock(parseJson(row.orderExtAttrs()));
    }

    private static String describe(FreezeLock lock) {
        return lock == null ? "null"
                : "{from=" + lock.from() + ", to=" + lock.to() + ", resourceCodes="
                + lock.normalizedResourceCodes() + "}";
    }

    private List<UUID> lockedIdsOf(AdjustTaskRow row) {
        if (row.lockedResourcesJson() == null || row.lockedResourcesJson().isBlank()) {
            return List.of();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(row.lockedResourcesJson(),
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            List<UUID> ids = new ArrayList<>(values.size());
            for (String value : values) {
                ids.add(UUID.fromString(value));
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private Map<String, Object> snapshot(UUID tenantId, AdjustTaskRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceCode", row.resourceCode());
        snapshot.put("startTime", text(row.startTime()));
        snapshot.put("endTime", text(row.endTime()));
        snapshot.put("setupStartTime", text(row.setupStartTime()));
        snapshot.put("setupEndTime", text(row.setupEndTime()));
        snapshot.put("status", row.status());
        snapshot.put("lockType", row.lockType());
        snapshot.put("lockFrom", text(row.lockedFrom()));
        snapshot.put("lockTo", text(row.lockedTo()));
        snapshot.put("lockedResourceCodes", codesOf(tenantId, lockedIdsOf(row)));
        snapshot.put("version", row.version());
        return snapshot;
    }

    private Map<String, Object> snapshot(PlannedState planned) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceCode", planned.resourceCode());
        snapshot.put("startTime", text(planned.startTime()));
        snapshot.put("endTime", text(planned.endTime()));
        snapshot.put("setupStartTime", text(planned.setupStartTime()));
        snapshot.put("setupEndTime", text(planned.setupEndTime()));
        snapshot.put("status", planned.status());
        snapshot.put("lockType", planned.lockType());
        snapshot.put("lockFrom", text(planned.lockedFrom()));
        snapshot.put("lockTo", text(planned.lockedTo()));
        snapshot.put("lockedResourceCodes", planned.lockedResourceIds() == null ? List.of()
                : codesOf(TenantContext.getTenantId(), planned.lockedResourceIds()));
        return snapshot;
    }

    private List<String> codesOf(UUID tenantId, List<UUID> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> codes = scheduleRepository.findResourceCodes(tenantId);
        return resourceIds.stream().map(codes::get).filter(code -> code != null).toList();
    }

    private PlannedState fromSnapshot(UUID tenantId, Map<String, Object> snapshot) {
        String resourceCode = stringValue(snapshot.get("resourceCode"));
        UUID resourceId = null;
        if (resourceCode != null) {
            TaskAdjustmentRepository.ResourceRef ref = adjustmentRepository
                    .findResourceByCode(tenantId, resourceCode)
                    .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                            "快照中的资源已不存在: " + resourceCode));
            resourceId = ref.resourceId();
        }
        List<UUID> lockedIds = new ArrayList<>();
        Object codes = snapshot.get("lockedResourceCodes");
        if (codes instanceof List<?> list) {
            for (Object code : list) {
                String text = stringValue(code);
                if (text == null) {
                    continue;
                }
                lockedIds.add(adjustmentRepository.findResourceByCode(tenantId, text)
                        .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                                "快照中的资源已不存在: " + text)).resourceId());
            }
        }
        return new PlannedState(resourceId, resourceCode,
                timeValue(snapshot.get("setupStartTime")), timeValue(snapshot.get("setupEndTime")),
                timeValue(snapshot.get("startTime")), timeValue(snapshot.get("endTime")),
                stringValue(snapshot.get("status")), stringValue(snapshot.get("lockType")),
                timeValue(snapshot.get("lockFrom")), timeValue(snapshot.get("lockTo")),
                List.copyOf(lockedIds), 0);
    }

    private TaskView toView(UUID tenantId, PlannedState planned, int version) {
        return new TaskView(planned.resourceCode(), planned.status(), version,
                planned.setupStartTime(), planned.setupEndTime(), planned.startTime(), planned.endTime(),
                planned.lockType(), planned.lockedFrom(), planned.lockedTo(),
                codesOf(tenantId, planned.lockedResourceIds()));
    }

    private static String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "operation 必填");
        }
        return operation.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("调整快照解析失败", exception);
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("调整快照序列化失败", exception);
        }
    }

    private static String text(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static OffsetDateTime timeValue(Object value) {
        String text = stringValue(value);
        return text == null ? null : OffsetDateTime.parse(text);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 调整目标状态（写入前的完整字段集合）。
     */
    private record PlannedState(UUID resourceId, String resourceCode, OffsetDateTime setupStartTime,
                                OffsetDateTime setupEndTime, OffsetDateTime startTime,
                                OffsetDateTime endTime, String status, String lockType,
                                OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                                List<UUID> lockedResourceIds, int operationIndex) {
    }

    /**
     * 人工调整命令（C22 {@code TaskAdjustRequest}）。
     *
     * <p>使用可变 POJO 而非 record：Jackson 对 record 的规范化构造器要求全部组件参与绑定，
     * 这里需要支持“只传 operation + version”这类部分字段请求。</p>
     */
    public static class TaskAdjustCommand {

        private String operation;

        private String resourceCode;

        private List<String> secondaryResourceCodes;

        private OffsetDateTime startTime;

        private OffsetDateTime lockFrom;

        private OffsetDateTime lockTo;

        private List<String> lockedResourceCodes;

        private String reason;

        private Integer version;

        private Boolean dryRun;

        /**
         * 无参构造，供 Jackson 反序列化。
         */
        public TaskAdjustCommand() {
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getResourceCode() {
            return resourceCode;
        }

        public void setResourceCode(String resourceCode) {
            this.resourceCode = resourceCode;
        }

        public List<String> getSecondaryResourceCodes() {
            return secondaryResourceCodes;
        }

        public void setSecondaryResourceCodes(List<String> secondaryResourceCodes) {
            this.secondaryResourceCodes = secondaryResourceCodes;
        }

        public OffsetDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(OffsetDateTime startTime) {
            this.startTime = startTime;
        }

        public OffsetDateTime getLockFrom() {
            return lockFrom;
        }

        public void setLockFrom(OffsetDateTime lockFrom) {
            this.lockFrom = lockFrom;
        }

        public OffsetDateTime getLockTo() {
            return lockTo;
        }

        public void setLockTo(OffsetDateTime lockTo) {
            this.lockTo = lockTo;
        }

        public List<String> getLockedResourceCodes() {
            return lockedResourceCodes;
        }

        public void setLockedResourceCodes(List<String> lockedResourceCodes) {
            this.lockedResourceCodes = lockedResourceCodes;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public Boolean getDryRun() {
            return dryRun;
        }

        public void setDryRun(Boolean dryRun) {
            this.dryRun = dryRun;
        }
    }

    /**
     * 调整结果（C22 {@code TaskAdjustResult}）。
     *
     * @param adjustmentId 调整记录主键；dryRun 为空
     * @param applied 是否已应用（dryRun 表示校验通过）
     * @param dryRun 是否仅校验
     * @param task 调整后的任务视图
     * @param conflicts 冲突列表，成功时为空
     */
    public record TaskAdjustResult(Long adjustmentId, boolean applied, boolean dryRun, TaskView task,
                                   List<TaskConflict> conflicts) {
    }

    /**
     * 冲突项。
     *
     * @param type 冲突类型
     * @param message 说明
     * @param relatedTaskIds 关联任务主键
     */
    public record TaskConflict(String type, String message, List<Long> relatedTaskIds) {
    }

    /**
     * 任务视图。
     *
     * @param resourceCode 主资源编码
     * @param status 任务状态
     * @param version 版本
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param lockType 锁类型
     * @param lockedFrom 锁窗开始
     * @param lockedTo 锁窗结束
     * @param lockedResourceCodes 锁定资源编码
     */
    public record TaskView(String resourceCode, String status, int version,
                           OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                           OffsetDateTime startTime, OffsetDateTime endTime, String lockType,
                           OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                           List<String> lockedResourceCodes) {
    }

    /**
     * 调整历史项。
     *
     * @param adjustmentId 调整记录主键
     * @param adjustType 调整类型
     * @param before 调整前快照
     * @param after 调整后快照
     * @param reason 原因
     * @param undone 是否已撤销
     * @param createdAt 创建时间
     */
    public record TaskAdjustmentView(Long adjustmentId, String adjustType,
                                     Map<String, Object> before, Map<String, Object> after,
                                     String reason, boolean undone, OffsetDateTime createdAt) {
    }

    /**
     * 调整历史分页。
     *
     * @param items 当前页
     * @param total 总条数
     * @param page 页码
     * @param size 页大小
     */
    public record AdjustmentPage(List<TaskAdjustmentView> items, int total, int page, int size) {
    }
}
