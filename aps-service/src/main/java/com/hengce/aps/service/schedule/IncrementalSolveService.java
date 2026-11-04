package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository.BaseSolveTask;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository.TaskRef;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 增量重排编排（C21 §24.3，M2-SE-04）。
 *
 * <p>职责边界：</p>
 * <ol>
 *   <li>复用基准 {@code solve_task} 提交时固化的配置（含 {@code softWeights} 快照），
 *       不再读取当前策略，保证权重不漂移；</li>
 *   <li>只对受影响订单重新求解，并写一条新的 {@code solve_task}；</li>
 *   <li>在计划域写一条新的 {@code DRAFT} {@code plan_version}，绝不覆盖已 PUBLISHED 快照；</li>
 *   <li>场景内存在 {@code IN_PROGRESS/COMPLETED} 任务时按场景级判定返回 40903；</li>
 *   <li>齐套未通过（{@code blocking=true} 且缺料）返回 42200，不复制 MRP 判定；</li>
 *   <li>含 {@code lock_type<>NONE} 或 {@code status=FROZEN} 的订单整体排除在本次重排之外，
 *       保证锁/冻结不被增量结果放宽（本 M2 版本不接入 startingSolution，见报告说明）。</li>
 * </ol>
 */
@Service
public class IncrementalSolveService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String UNLOCKED = "NONE";

    private final IncrementalSolveRepository incrementalRepository;

    private final PlanVersionRepository planVersionRepository;

    private final MrpBlockingChecker mrpBlockingChecker;

    private final SolveTaskLifecycleService lifecycleService;

    /**
     * 构造服务。
     *
     * @param incrementalRepository 增量持久层
     * @param planVersionRepository 计划版本持久层
     * @param mrpBlockingChecker MRP 阻塞判定
     * @param lifecycleService 求解任务生命周期
     */
    public IncrementalSolveService(IncrementalSolveRepository incrementalRepository,
                                   PlanVersionRepository planVersionRepository,
                                   MrpBlockingChecker mrpBlockingChecker,
                                   SolveTaskLifecycleService lifecycleService) {
        this.incrementalRepository = incrementalRepository;
        this.planVersionRepository = planVersionRepository;
        this.mrpBlockingChecker = mrpBlockingChecker;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 提交增量重排。
     *
     * @param request 增量重排请求
     * @param idempotencyKey 幂等键，可空
     * @return 新 planId / 新 solveTask 详情
     */
    public IncrementalSolveResult submit(IncrementalSolveRequest request, String idempotencyKey) {
        if (request == null || request.getBaseSolveTaskId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "baseSolveTaskId 必填");
        }
        UUID tenantId = TenantContext.getTenantId();
        BaseSolveTask base = incrementalRepository.findSolveTask(tenantId, request.getBaseSolveTaskId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "基准求解任务不存在: " + request.getBaseSolveTaskId()));
        UUID sandboxId = request.getSandboxId() != null ? request.getSandboxId()
                : base.sandboxId() != null ? base.sandboxId() : ScheduleProblem.DEFAULT_SANDBOX_ID;

        int executed = planVersionRepository.countExecutedTasks(tenantId, sandboxId);
        if (executed > 0) {
            throw new BizException(ErrorCode.PUBLISHED_CONFLICT,
                    "场景内存在已执行任务（IN_PROGRESS/COMPLETED）" + executed + " 条，"
                            + "按场景级判定拒绝增量重排");
        }

        List<TaskRef> sceneTasks = incrementalRepository.findTasks(tenantId, sandboxId);
        Set<Long> affected = new HashSet<>();
        if (request.getAffectedTaskIds() != null && !request.getAffectedTaskIds().isEmpty()) {
            Set<Long> sceneIds = new HashSet<>();
            for (TaskRef task : sceneTasks) {
                sceneIds.add(task.taskId());
            }
            for (Long taskId : request.getAffectedTaskIds()) {
                if (taskId == null || !sceneIds.contains(taskId)) {
                    throw new BizException(ErrorCode.NOT_FOUND,
                            "affectedTaskIds 中存在不属于该场景的任务: " + taskId);
                }
                affected.add(taskId);
            }
        } else {
            for (TaskRef task : sceneTasks) {
                affected.add(task.taskId());
            }
        }

        Set<UUID> frozenOrderIds = new HashSet<>(incrementalRepository.findFrozenOrderIds(tenantId, sandboxId));
        Set<String> orderNos = new LinkedHashSet<>();
        int skippedLockedTasks = 0;
        for (TaskRef task : sceneTasks) {
            if (!affected.contains(task.taskId())) {
                continue;
            }
            if (frozenOrderIds.contains(task.orderId())) {
                skippedLockedTasks++;
                continue;
            }
            if ("FROZEN".equals(task.lockType()) || "FROZEN".equals(task.status())) {
                skippedLockedTasks++;
                continue;
            }
            if (task.orderNo() != null) {
                orderNos.add(task.orderNo());
            }
        }
        if (orderNos.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有可增量重排的订单（相关任务均已冻结/锁定）");
        }

        List<String> orders = List.copyOf(orderNos);
        MrpBlockingDecision decision = mrpBlockingChecker.evaluate(sandboxId, orders, true);
        if (decision.blocked()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, decision.message());
        }

        Set<UUID> affectedOrderIds = new HashSet<>();
        for (TaskRef task : sceneTasks) {
            if (task.orderNo() != null && orderNos.contains(task.orderNo())) {
                affectedOrderIds.add(task.orderId());
            }
        }
        List<String> pinnedOrderNos = sceneTasks.stream()
                .filter(task -> !affectedOrderIds.contains(task.orderId()))
                .map(TaskRef::orderNo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<String, Object> parameters = new LinkedHashMap<>(baseParameters(base.configJson()));
        if (!pinnedOrderNos.isEmpty()) {
            parameters.put(ScheduleProblemAssembler.PARAM_PINNED_ORDER_NOS, pinnedOrderNos);
        }
        String taskNo = "INC-" + base.solveTaskId() + "-" + System.currentTimeMillis();
        SolveRequest solveRequest = new SolveRequest(taskNo, sandboxId, base.strategyId(),
                base.engine(), orders, parameters);

        String datePart = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String planNo = planVersionRepository.nextPlanNo(tenantId, datePart);
        int versionNo = planVersionRepository.nextVersionNo(tenantId, sandboxId);
        String reason = request.getReason() == null || request.getReason().isBlank()
                ? "增量重排：baseSolveTaskId=" + base.solveTaskId()
                : request.getReason().trim();
        UUID planId = incrementalRepository.insertDraftVersion(tenantId, sandboxId, planNo, versionNo,
                reason, AuditContext.getCurrentUserId());

        SolveTaskDetail detail = lifecycleService.submit(solveRequest, idempotencyKey);
        return new IncrementalSolveResult(planId, detail.taskId(), base.solveTaskId(), sandboxId,
                orders.size(), orders, skippedLockedTasks, detail);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseParameters(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> config = OBJECT_MAPPER.readValue(configJson, MAP_TYPE);
            Object parameters = config.get("parameters");
            return parameters instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("基准求解配置解析失败", exception);
        }
    }

    /**
     * 增量重排请求（C21 §24.3）。
     *
     * <p>可变 POJO：Jackson 对 record 的规范化构造器要求全部组件参与绑定，而这里允许
     * 只传 {@code baseSolveTaskId}（其余字段空表示场景内全部非冻结任务）。</p>
     */
    public static class IncrementalSolveRequest {

        private Long baseSolveTaskId;

        private List<Long> affectedTaskIds;

        private UUID sandboxId;

        private String reason;

        /**
         * 无参构造，供 Jackson 反序列化。
         */
        public IncrementalSolveRequest() {
        }

        public Long getBaseSolveTaskId() {
            return baseSolveTaskId;
        }

        public void setBaseSolveTaskId(Long baseSolveTaskId) {
            this.baseSolveTaskId = baseSolveTaskId;
        }

        public List<Long> getAffectedTaskIds() {
            return affectedTaskIds;
        }

        public void setAffectedTaskIds(List<Long> affectedTaskIds) {
            this.affectedTaskIds = affectedTaskIds;
        }

        public UUID getSandboxId() {
            return sandboxId;
        }

        public void setSandboxId(UUID sandboxId) {
            this.sandboxId = sandboxId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /**
     * 增量重排结果。
     *
     * @param planId 新建的 DRAFT 计划版本主键（对外 planId）
     * @param solveTaskId 新求解任务主键
     * @param baseSolveTaskId 基准求解任务主键
     * @param sandboxId 场景标识
     * @param affectedOrderCount 参与重排的订单数
     * @param orderNos 参与重排的订单号
     * @param skippedLockedTaskCount 因锁定/冻结被跳过的任务数
     * @param solveTask 求解任务详情
     */
    public record IncrementalSolveResult(UUID planId, long solveTaskId, long baseSolveTaskId,
                                         UUID sandboxId, int affectedOrderCount, List<String> orderNos,
                                         int skippedLockedTaskCount, SolveTaskDetail solveTask) {
    }
}
