package com.hengce.aps.service.schedule;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingEdgeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingNodeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.OperationTemplateMapper;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingEdge;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.FrozenTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.duration.OperationRunDuration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 求解问题装配：把 DB 中的订单、ACTIVE 工艺路线、资源、日历与冻结任务映射为 {@link ScheduleProblem}。
 *
 * <p>数据来源遵循 BD-03/04/05/06/07 的读取约定：{@code routing.nodes/edges} JSONB 只是画布快照，
 * 事实来源是 {@code routing_node}/{@code routing_edge}。跨域主数据只读复用既有 Mapper，
 * 不复制对方的实体。</p>
 */
@Service
public class ScheduleProblemAssembler {

    /**
     * 可用时段向前后各扩展的小时数，覆盖求解器以自身 now 为基准的 7 天 horizon。
     */
    private static final int HORIZON_MARGIN_HOURS = 2;

    /**
     * 求解器 horizon 小时数，与 TimefoldModelBuilder 保持一致。
     */
    private static final int HORIZON_HOURS = 168;

    /**
     * 路线分页查询页大小。
     */
    private static final int ROUTING_PAGE_SIZE = 5;

    /**
     * 日历分页查询页大小。
     */
    private static final int CALENDAR_PAGE_SIZE = 50;

    /**
     * 支持的依赖类型。
     */
    private static final List<String> SUPPORTED_DEPEND_TYPES = List.of("FS", "SS", "FF");

    /**
     * 排产持久层。
     */
    private final ScheduleRepository scheduleRepository;

    /**
     * 工艺路线 Mapper。
     */
    private final RoutingMapper routingMapper;

    /**
     * 工艺路线节点 Mapper。
     */
    private final RoutingNodeMapper routingNodeMapper;

    /**
     * 工艺路线依赖边 Mapper。
     */
    private final RoutingEdgeMapper routingEdgeMapper;

    /**
     * 工序模版 Mapper。
     */
    private final OperationTemplateMapper operationTemplateMapper;

    /**
     * 资源 Mapper。
     */
    private final ResourceMapper resourceMapper;

    /**
     * 资源组 Mapper。
     */
    private final ResourceGroupMapper resourceGroupMapper;

    /**
     * 日历 Mapper。
     */
    private final CalendarMapper calendarMapper;

    /**
     * 构造装配器。
     *
     * @param scheduleRepository 排产持久层
     * @param routingMapper 路线 Mapper
     * @param routingNodeMapper 节点 Mapper
     * @param routingEdgeMapper 边 Mapper
     * @param operationTemplateMapper 模版 Mapper
     * @param resourceMapper 资源 Mapper
     * @param resourceGroupMapper 资源组 Mapper
     * @param calendarMapper 日历 Mapper
     */
    public ScheduleProblemAssembler(
            ScheduleRepository scheduleRepository,
            RoutingMapper routingMapper,
            RoutingNodeMapper routingNodeMapper,
            RoutingEdgeMapper routingEdgeMapper,
            OperationTemplateMapper operationTemplateMapper,
            ResourceMapper resourceMapper,
            ResourceGroupMapper resourceGroupMapper,
            CalendarMapper calendarMapper) {
        this.scheduleRepository = scheduleRepository;
        this.routingMapper = routingMapper;
        this.routingNodeMapper = routingNodeMapper;
        this.routingEdgeMapper = routingEdgeMapper;
        this.operationTemplateMapper = operationTemplateMapper;
        this.resourceMapper = resourceMapper;
        this.resourceGroupMapper = resourceGroupMapper;
        this.calendarMapper = calendarMapper;
    }

    /**
     * 增量重排参数：需要以既有任务固定进题面的订单号（未受影响订单）。
     */
    public static final String PARAM_PINNED_ORDER_NOS = "pinnedOrderNos";

    /**
     * 从数据库装配求解问题。
     *
     * @param request 求解请求
     * @param config 求解配置
     * @return 装配结果，含问题快照、参与订单与降级信息
     */
    public AssembledProblem assemble(SolveRequest request, SolveConfig config) {
        UUID tenantId = TenantContext.getTenantId();
        UUID sandboxId = request.sandboxId() == null
                ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();

        boolean allOrders = request.orderNos() == null || request.orderNos().isEmpty();
        List<OrderSourceRow> requestedRows = scheduleRepository.findOrders(
                tenantId, request.orderNos(), allOrders);
        List<OrderSourceRow> pinnedRows = allOrders
                ? List.of() : findPinnedOrders(tenantId, request.orderNos(),
                readStringList(request.parameters() == null
                        ? null : request.parameters().get(PARAM_PINNED_ORDER_NOS)));
        List<OrderSourceRow> orderRows = new ArrayList<>(requestedRows);
        orderRows.addAll(pinnedRows);
        if (orderRows.isEmpty()) {
            return new AssembledProblem(tenantId, sandboxId, new ScheduleProblem(
                    tenantId.toString(), sandboxId, null, request.strategyId(), config.direction(),
                    List.of(), List.of(), List.of(), List.of(), List.of()), List.of());
        }

        OffsetDateTime horizonStart = OffsetDateTime.now(ZoneOffset.UTC)
                .minusHours(HORIZON_MARGIN_HOURS)
                .withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime horizonEnd = horizonStart.plusHours(HORIZON_HOURS + 2 * HORIZON_MARGIN_HOURS);

        List<SchedulingResource> resources = buildResources(horizonStart, horizonEnd);
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : resources) {
            resourceById.put(resource.resourceId(), resource);
        }
        if (resourceById.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY, "租户下没有可用资源，无法排产");
        }

        Map<String, OperationTemplate> templateCache = new HashMap<>();
        Map<String, List<RoutingNode>> nodeCache = new HashMap<>();
        List<SchedulingOrder> orders = new ArrayList<>();
        for (OrderSourceRow order : orderRows) {
            Routing routing = resolveRouting(order);
            UUID routingId = requireUuid(routing.getId());
            List<RoutingNode> nodes = nodeCache.computeIfAbsent(
                    routingId.toString(), key -> routingNodeMapper.selectByRoutingId(routingId));
            if (nodes.isEmpty()) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + order.orderNo() + " 的 ACTIVE 工艺路线没有节点");
            }
            List<RoutingEdge> edges = routingEdgeMapper.selectByRoutingId(routingId);
            Map<UUID, Integer> seqByNodeId = new HashMap<>();
            for (RoutingNode node : nodes) {
                seqByNodeId.put(requireUuid(node.getId()), node.getSeqNo());
            }
            Map<UUID, List<OperationDependency>> dependenciesByNode = dependencies(edges, seqByNodeId, order.orderNo());
            List<SchedulingOperation> operations = new ArrayList<>();
            for (RoutingNode node : nodes) {
                operations.add(toOperation(node, templateCache,
                        dependenciesByNode.get(requireUuid(node.getId())), resourceById));
            }
            orders.add(new SchedulingOrder(
                    order.orderId(),
                    order.orderNo(),
                    order.materialCode(),
                    order.productFamily(),
                    order.qty(),
                    order.dueDate(),
                    order.priority(),
                    order.freezeStatus(),
                    routingId,
                    operations));
        }

        List<TaskAssignment> pinnedAssignments = buildFrozenAssignments(
                scheduleRepository.findTasksByOrders(tenantId, sandboxId,
                        pinnedRows.stream().map(OrderSourceRow::orderId).toList()), true);
        List<TaskAssignment> frozenAssignments = mergeAssignments(
                pinnedAssignments,
                buildFrozenAssignments(scheduleRepository.findFrozenTasks(tenantId, sandboxId), false));
        validateFloatOperationDurations(orders, resourceById, frozenAssignments);

        ScheduleProblem problem = new ScheduleProblem(
                tenantId.toString(),
                sandboxId,
                null,
                request.strategyId(),
                config.direction(),
                orders,
                resources,
                frozenAssignments,
                List.of(),
                List.of());
        return new AssembledProblem(tenantId, sandboxId, problem,
                requestedRows.stream().map(OrderSourceRow::orderId).toList());
    }

    /**
     * 校验 {@code pinnedOrderNos} 与 {@code orderNos} 无交集，并返回去重后的 pin 订单号。
     *
     * <p>输入自相矛盾时不能自己挑一个解释继续跑：同一订单既要求“钉住不动”又要求“参与重排”，
     * 静默剔除只会让调用方以为 pin 生效，实测会让整组对照结果失效。这里直接以 40000 拒绝，
     * 并在 message 中指名重叠订单。</p>
     *
     * @param requestedOrderNos 参与求解的订单号
     * @param pinnedOrderNos 需要 pin 的订单号
     * @return 去重后的 pin 订单号；无 pin 时返回空列表
     */
    static List<String> requireDisjointPinnedOrders(
            List<String> requestedOrderNos, List<String> pinnedOrderNos) {
        List<String> pinned = pinnedOrderNos == null ? List.of() : pinnedOrderNos;
        if (pinned.isEmpty()) {
            return List.of();
        }
        Set<String> requested = new HashSet<>(requestedOrderNos == null ? List.of() : requestedOrderNos);
        Set<String> overlap = new LinkedHashSet<>(pinned);
        overlap.retainAll(requested);
        if (!overlap.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "pinnedOrderNos 与 orderNos 存在重叠订单: " + String.join("、", overlap)
                            + "；钉住的订单不能同时作为重排目标");
        }
        return pinned.stream().distinct().toList();
    }

    /**
     * 受理前的参数自洽性校验：从 {@code parameters} 读取 {@code pinnedOrderNos} 并检查交集。
     *
     * <p>放在提交入口而不是只放在装配阶段，是为了在异步模式下也能直接返回 400，
     * 而不是先受理成 PENDING、再在后台降级成 FAILED。</p>
     *
     * @param requestedOrderNos 参与求解的订单号
     * @param parameters 求解参数，可空
     */
    public static void validatePinnedOrderNos(
            List<String> requestedOrderNos, Map<String, Object> parameters) {
        requireDisjointPinnedOrders(requestedOrderNos,
                readStringList(parameters == null ? null : parameters.get(PARAM_PINNED_ORDER_NOS)));
    }

    /**
     * 读取需要 pin 进题面的未受影响订单；与求解集合重叠时拒绝求解。
     *
     * @param tenantId 租户标识
     * @param requestedOrderNos 参与求解的订单号
     * @param pinnedOrderNos 需要 pin 的订单号
     * @return 去重后的订单行
     */
    private List<OrderSourceRow> findPinnedOrders(
            UUID tenantId, List<String> requestedOrderNos, List<String> pinnedOrderNos) {
        List<String> filtered = requireDisjointPinnedOrders(requestedOrderNos, pinnedOrderNos);
        if (filtered.isEmpty()) {
            return List.of();
        }
        return scheduleRepository.findOrders(tenantId, filtered, false);
    }

    /**
     * 解析请求参数中的订单号列表。
     *
     * @param value 参数值
     * @return 订单号列表，空值返回空列表
     */
    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * 合并分配：pinned 优先，按 taskId 去重。
     *
     * @param pinned 固定分配
     * @param frozen 锁定分配
     * @return 合并后的分配
     */
    private static List<TaskAssignment> mergeAssignments(
            List<TaskAssignment> pinned, List<TaskAssignment> frozen) {
        Map<Long, TaskAssignment> merged = new LinkedHashMap<>();
        for (TaskAssignment assignment : pinned) {
            merged.put(assignment.taskId(), assignment);
        }
        for (TaskAssignment assignment : frozen) {
            merged.putIfAbsent(assignment.taskId(), assignment);
        }
        return List.copyOf(merged.values());
    }

    private Routing resolveRouting(OrderSourceRow order) {
        if (order.routingId() != null) {
            Routing routing = routingMapper.selectById(order.routingId());
            if (routing != null) {
                return routing;
            }
        }
        List<Routing> actives = routingMapper.selectPageByFilter(
                new Page<>(1, ROUTING_PAGE_SIZE), order.materialCode(), null, "ACTIVE",
                "effective_date", "DESC")
                .getRecords();
        if (actives.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "订单 " + order.orderNo() + " 的产品 " + order.materialCode() + " 没有 ACTIVE 工艺路线");
        }
        return actives.get(0);
    }

    private Map<UUID, List<OperationDependency>> dependencies(
            List<RoutingEdge> edges, Map<UUID, Integer> seqByNodeId, String orderNo) {
        Map<UUID, List<OperationDependency>> result = new LinkedHashMap<>();
        for (RoutingEdge edge : edges) {
            Integer fromSeq = seqByNodeId.get(edge.getFromNodeId());
            Integer toSeq = seqByNodeId.get(edge.getToNodeId());
            if (fromSeq == null || toSeq == null) {
                continue;
            }
            String dependType = edge.getDependType() == null
                    ? "FS" : edge.getDependType().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_DEPEND_TYPES.contains(dependType)) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + orderNo + " 的工序依赖类型 " + dependType
                                + " 不在 V1 支持范围（FS/SS/FF），已拒绝求解");
            }
            int lagMinutes = edge.getLagMinutes() == null ? 0 : edge.getLagMinutes();
            if (lagMinutes < 0) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + orderNo + " 的工序依赖 lagMinutes 不能为负数");
            }
            result.computeIfAbsent(edge.getToNodeId(), key -> new ArrayList<>())
                    .add(new OperationDependency(fromSeq, dependType, lagMinutes));
        }
        return result;
    }

    private SchedulingOperation toOperation(RoutingNode node, Map<String, OperationTemplate> templateCache,
                                            List<OperationDependency> dependencies,
                                            Map<UUID, SchedulingResource> resourceById) {
        OperationTemplate template = null;
        if (node.getOperationTemplateId() != null) {
            template = templateCache.computeIfAbsent(node.getOperationTemplateId().toString(),
                    key -> operationTemplateMapper.selectById(node.getOperationTemplateId()));
        }
        Integer standardSeconds = node.getStandardSeconds();
        if (standardSeconds == null && template != null) {
            standardSeconds = template.getStandardSeconds();
        }
        if (standardSeconds == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工序 " + node.getNodeKey() + " 缺少标准工时（routing_node/operation_template 均为空）");
        }
        Integer setupSeconds = node.getSetupSeconds();
        if (setupSeconds == null && template != null) {
            setupSeconds = template.getSetupSeconds();
        }
        UUID primaryResourceId = primaryResourceId(node, template, resourceById);
        UUID primaryResourceGroupId = template == null ? null : template.getPrimaryResourceGroupId();
        List<UUID> secondaryResourceIds = secondaryResourceIds(node, template);
        String operationCode = node.getOperationCode() != null
                ? node.getOperationCode() : template == null ? null : template.getCode();
        String operationName = node.getOperationName() != null
                ? node.getOperationName() : template == null ? null : template.getName();
        return new SchedulingOperation(
                node.getSeqNo(),
                operationCode,
                operationName,
                node.getOperationTemplateId(),
                BigDecimal.valueOf(standardSeconds),
                BigDecimal.valueOf(setupSeconds == null ? 0 : setupSeconds),
                primaryResourceId,
                primaryResourceGroupId,
                secondaryResourceIds,
                Boolean.TRUE.equals(node.getIsOptional()),
                dependencies == null ? List.of() : dependencies);
    }

    /**
     * 浮动工序候选资源时长一致性前置校验（C15 §20.3.1.4）。
     *
     * <p>求解模型在建模型时为每个任务只算一次 {@code runSeconds} 并写死，换资源不重算；
     * 因此浮动工序的候选集合内各资源算出的 {@code runSeconds} 必须一致。候选集合：
     * 任务带 {@code RESOURCE} 锁且集合非空时取锁集合，否则取场景内全部启用资源。
     * 不一致时装配阶段直接拒绝（{@code 42200}），不静默取第一个资源代算。</p>
     *
     * <p>固定主资源工序只有一个候选，无需校验。</p>
     *
     * @param orders 参与求解的订单
     * @param resourceById 场景内资源
     * @param assignments 既有分配（携带任务级 {@code RESOURCE} 锁）
     */
    private void validateFloatOperationDurations(
            List<SchedulingOrder> orders,
            Map<UUID, SchedulingResource> resourceById,
            List<TaskAssignment> assignments) {
        Map<String, TaskAssignment> assignmentByKey = new HashMap<>();
        for (TaskAssignment assignment : assignments) {
            assignmentByKey.put(orderTaskKey(assignment.orderId(), assignment.operationIndex()), assignment);
        }
        List<String> mismatches = new ArrayList<>();
        for (SchedulingOrder order : orders) {
            for (SchedulingOperation operation : order.operations()) {
                if (operation.primaryResourceId() != null) {
                    continue;
                }
                TaskAssignment existing = assignmentByKey.get(
                        orderTaskKey(order.orderId(), operation.operationIndex()));
                List<SchedulingResource> candidates = candidateResources(resourceById, existing);
                if (candidates.size() <= 1) {
                    continue;
                }
                Map<Long, List<String>> codesByRunSeconds = new TreeMap<>();
                for (SchedulingResource resource : candidates) {
                    codesByRunSeconds.computeIfAbsent(
                            OperationRunDuration.seconds(resource, operation, order.qty()),
                            key -> new ArrayList<>()).add(resource.code());
                }
                if (codesByRunSeconds.size() > 1) {
                    String operationLabel = operation.operationCode() == null
                            ? "OP" + operation.operationIndex() : operation.operationCode();
                    mismatches.add("订单 " + order.orderNo() + " / 工序 " + operationLabel
                            + "（序号 " + operation.operationIndex() + "）候选资源: "
                            + codesByRunSeconds.entrySet().stream()
                            .map(entry -> String.join("/", entry.getValue())
                                    + "(runSeconds=" + entry.getKey() + ")")
                            .collect(Collectors.joining(", ")));
                }
            }
        }
        if (!mismatches.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "浮动工序的候选资源算出的 runSeconds 不一致，已拒绝求解（C15 §20.3.1.4）: "
                            + String.join("; ", mismatches));
        }
    }

    /**
     * 浮动工序的候选资源集合：有 {@code RESOURCE} 锁取锁集合，否则取全场景。
     *
     * @param resourceById 场景内资源（按 code 升序）
     * @param existing 既有分配，可空
     * @return 候选资源
     */
    private List<SchedulingResource> candidateResources(
            Map<UUID, SchedulingResource> resourceById, TaskAssignment existing) {
        if (existing != null && "RESOURCE".equals(existing.lockType())
                && existing.lockedResourceIds() != null && !existing.lockedResourceIds().isEmpty()) {
            List<SchedulingResource> locked = existing.lockedResourceIds().stream()
                    .map(resourceById::get)
                    .filter(resource -> resource != null)
                    .toList();
            if (!locked.isEmpty()) {
                return locked;
            }
        }
        return List.copyOf(resourceById.values());
    }

    /**
     * 任务键：订单标识 + 工序序号。
     *
     * @param orderId 订单标识
     * @param operationIndex 工序序号
     * @return 任务键
     */
    private static String orderTaskKey(UUID orderId, int operationIndex) {
        return orderId + "#" + operationIndex;
    }

    private UUID primaryResourceId(RoutingNode node, OperationTemplate template,
                                   Map<UUID, SchedulingResource> resourceById) {
        Map<String, Object> overrides = node.getOverrides() == null ? Map.of() : node.getOverrides();
        if (overrides.containsKey("primaryResourceId")) {
            Object value = overrides.get("primaryResourceId");
            if (value == null) {
                // 显式 null：工序主资源浮动，由求解器在资源锁/可用资源范围内选择
                return null;
            }
            UUID override = uuidValue(value);
            if (override != null) {
                requireResource(override, node, resourceById);
                return override;
            }
        }
        UUID fromTemplate = template == null ? null : template.getPrimaryResourceId();
        if (fromTemplate != null) {
            requireResource(fromTemplate, node, resourceById);
            return fromTemplate;
        }
        UUID groupId = template == null ? null : template.getPrimaryResourceGroupId();
        if (groupId != null) {
            return resourceById.values().stream()
                    .filter(resource -> groupId.equals(resource.groupId()))
                    .map(SchedulingResource::resourceId)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "工序 " + node.getNodeKey() + " 的主资源组下没有资源"));
        }
        if (resourceById.size() == 1) {
            return resourceById.values().iterator().next().resourceId();
        }
        throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                "工序 " + node.getNodeKey() + " 缺少主资源，无法排产");
    }

    private List<UUID> secondaryResourceIds(RoutingNode node, OperationTemplate template) {
        Map<String, Object> overrides = node.getOverrides() == null ? Map.of() : node.getOverrides();
        if (overrides.containsKey("secondaryResourceIds")) {
            Object value = overrides.get("secondaryResourceIds");
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>(list.size());
            for (Object item : list) {
                UUID id = uuidValue(item);
                if (id != null) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }
        return template == null || template.getSecondaryResourceIds() == null
                ? List.of() : List.copyOf(template.getSecondaryResourceIds());
    }

    private void requireResource(UUID resourceId, RoutingNode node,
                                 Map<UUID, SchedulingResource> resourceById) {
        if (!resourceById.containsKey(resourceId)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工序 " + node.getNodeKey() + " 引用的资源不存在或已删除: " + resourceId);
        }
    }

    private List<SchedulingResource> buildResources(OffsetDateTime from, OffsetDateTime to) {
        List<Resource> resources = new ArrayList<>(resourceMapper.selectList(null));
        resources.sort(Comparator.comparing(Resource::getCode));
        Calendar factoryCalendar = defaultFactoryCalendar();
        Map<UUID, List<AvailabilityWindow>> windowCache = new HashMap<>();
        List<SchedulingResource> result = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            UUID calendarId = resource.getCalendarId();
            if (calendarId == null && resource.getGroupId() != null) {
                ResourceGroup group = resourceGroupMapper.selectById(resource.getGroupId());
                if (group != null) {
                    calendarId = group.getCalendarId();
                }
            }
            Calendar calendar = calendarId == null ? factoryCalendar : calendarMapper.findById(calendarId);
            UUID effectiveCalendarId = calendar == null || !(calendar.getId() instanceof UUID id) ? null : id;
            List<AvailabilityWindow> windows = windowCache.computeIfAbsent(
                    effectiveCalendarId, key -> expand(calendar, from, to));
            result.add(new SchedulingResource(
                    requireUuid(resource.getId()),
                    resource.getCode(),
                    resource.getType() == null ? null : resource.getType().name(),
                    resource.getGroupId(),
                    resource.getCapacityMode() == null ? null : resource.getCapacityMode().name(),
                    resource.getCapacityValue(),
                    resource.getBatchCapacity(),
                    resource.getEfficiency() == null ? BigDecimal.ONE : resource.getEfficiency(),
                    calendarId,
                    "ACTIVE",
                    windows));
        }
        return result;
    }

    private Calendar defaultFactoryCalendar() {
        List<Calendar> calendars = calendarMapper.selectPage(
                new Page<>(1, CALENDAR_PAGE_SIZE), null, "FACTORY", null, null);
        return calendars.stream()
                .filter(calendar -> calendar.getEffectiveFrom() == null
                        || !calendar.getEffectiveFrom().isAfter(LocalDate.now(ZoneOffset.UTC)))
                .max(Comparator.comparing(calendar -> calendar.getCalendarVersion() == null
                        ? 0 : calendar.getCalendarVersion()))
                .orElse(null);
    }

    /**
     * 把日历班次/例外日期展开为 UTC 半开可用窗口，按 start 升序且互不重叠。
     *
     * @param calendar 日历，可空
     * @param from 时间窗开始
     * @param to 时间窗结束
     * @return 可用时段；展开为空时返回空列表，由求解器标记 calendarFallback
     */
    List<AvailabilityWindow> expand(Calendar calendar, OffsetDateTime from, OffsetDateTime to) {
        if (calendar == null || calendar.getShifts() == null || calendar.getShifts().isEmpty()) {
            return List.of();
        }
        if (calendar.getEffectiveFrom() != null
                && calendar.getEffectiveFrom().isAfter(to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate())) {
            return List.of();
        }
        LocalDate cursor = from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        LocalDate last = to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (calendar.getEffectiveFrom() != null && calendar.getEffectiveFrom().isAfter(cursor)) {
            cursor = calendar.getEffectiveFrom();
        }
        if (calendar.getEffectiveTo() != null && calendar.getEffectiveTo().isBefore(last)) {
            last = calendar.getEffectiveTo();
        }
        Map<LocalDate, String> holidayKind = new HashMap<>();
        if (calendar.getHolidays() != null) {
            for (Map<String, Object> holiday : calendar.getHolidays()) {
                Object date = holiday.get("date");
                if (date == null) {
                    continue;
                }
                holidayKind.put(LocalDate.parse(String.valueOf(date)),
                        holiday.get("kind") == null ? "LEGAL" : String.valueOf(holiday.get("kind")));
            }
        }
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (LocalDate date = cursor; !date.isAfter(last); date = date.plusDays(1)) {
            String kind = holidayKind.get(date);
            if ("LEGAL".equalsIgnoreCase(kind) || "COMPANY".equalsIgnoreCase(kind)) {
                continue;
            }
            for (Map<String, Object> shift : calendar.getShifts()) {
                if (!applies(shift, date)) {
                    continue;
                }
                LocalTime startTime = parseTime(shift.get("startTime"));
                LocalDateTime start = date.atTime(startTime);
                LocalDateTime end = shiftEnd(date, startTime, shift.get("endTime"));
                addWindow(windows, start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC), from, to);
            }
        }
        windows.sort(Comparator.comparing(AvailabilityWindow::start));
        List<AvailabilityWindow> merged = new ArrayList<>(windows.size());
        for (AvailabilityWindow window : windows) {
            if (!merged.isEmpty() && !window.start().isAfter(merged.get(merged.size() - 1).end())) {
                AvailabilityWindow previous = merged.remove(merged.size() - 1);
                OffsetDateTime end = window.end().isAfter(previous.end()) ? window.end() : previous.end();
                if (end.isAfter(previous.start())) {
                    merged.add(new AvailabilityWindow(previous.start(), end));
                }
            } else {
                merged.add(window);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean applies(Map<String, Object> shift, LocalDate date) {
        Object days = shift.get("daysOfWeek");
        if (days instanceof List<?> list && !list.isEmpty()) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            for (Object item : list) {
                if (item instanceof Number number && number.intValue() == dayOfWeek) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static void addWindow(List<AvailabilityWindow> windows, OffsetDateTime start,
                                  OffsetDateTime end, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime clippedStart = start.isBefore(from) ? from : start;
        OffsetDateTime clippedEnd = end.isAfter(to) ? to : end;
        if (clippedEnd.isAfter(clippedStart)) {
            windows.add(new AvailabilityWindow(clippedStart, clippedEnd));
        }
    }

    private static LocalTime parseTime(Object value) {
        String text = String.valueOf(value == null ? "00:00" : value).trim();
        String[] parts = text.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return LocalTime.of(hour, minute);
    }

    private static LocalDateTime shiftEnd(LocalDate date, LocalTime startTime, Object value) {
        String text = String.valueOf(value == null ? "24:00" : value).trim();
        if ("24:00".equals(text)) {
            return date.plusDays(1).atStartOfDay();
        }
        LocalTime endTime = parseTime(text);
        if (endTime.equals(LocalTime.MIDNIGHT) || !endTime.isAfter(startTime)) {
            return date.plusDays(1).atTime(endTime);
        }
        return date.atTime(endTime);
    }

    private static List<TaskAssignment> buildFrozenAssignments(
            List<FrozenTaskRow> rows, boolean forcePinned) {
        List<TaskAssignment> assignments = new ArrayList<>(rows.size());
        for (FrozenTaskRow row : rows) {
            boolean frozenLevel = forcePinned
                    || "FROZEN".equals(row.status())
                    || "FROZEN".equals(row.lockType());
            assignments.add(new TaskAssignment(
                    row.taskId(),
                    row.orderId(),
                    row.orderNo(),
                    row.operationIndex(),
                    null,
                    row.operationTemplateId(),
                    row.resourceId(),
                    row.resourceCode(),
                    row.secondaryResourceIds(),
                    row.setupStartTime(),
                    row.setupEndTime() == null ? row.startTime() : row.setupEndTime(),
                    row.startTime(),
                    row.endTime(),
                    row.qty(),
                    null,
                    row.status(),
                    frozenLevel,
                    row.version(),
                    row.lockType(),
                    row.lockedFrom(),
                    row.lockedTo(),
                    row.lockedResourceIds()));
        }
        return List.copyOf(assignments);
    }

    private static UUID uuidValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID requireUuid(java.io.Serializable value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    /**
     * 装配结果。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param problem 求解问题
     * @param orderIds 参与订单主键
     */
    public record AssembledProblem(UUID tenantId, UUID sandboxId, ScheduleProblem problem,
                                   List<UUID> orderIds) {
    }
}
