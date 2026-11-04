# M1 契约冻结（S0 契约守门）

> 状态：**冻结**（2026-11-04）
> 适用：M1 全部业务流（主数据、工艺订单、求解、测试部署）
> 事实来源：`mfg-aps-platform-PRD.md`、`db/migration/V1__init.sql`、`docs/db/schema.md`、`docs/api/conventions.md`、`docs/api/openapi/*.yaml`
> 责任：S0 只做契约冻结、依赖预置与收口，**不实现业务逻辑**，不发号以外的迁移。

本文件是 M1 期间跨流协作的唯一契约基线。业务流实现与本文件冲突时，以本文件为准；需要变更时走第 0.1 节的契约变更流程。

---

## 0. 冻结规则

### 0.1 契约变更流程（已确认）

1. 任何字段/路径/枚举/错误码变更，先提交「契约变更单」：说明变更点、影响流、兼容性（是否破坏 `/api/v1`）。
2. 破坏性变更（删除/重命名字段、改路径、改语义）**不得**在 M1 内直接落地，必须走 `/api/v2` 或延后。
3. 兼容性变更（新增可选字段、新增枚举值）由 S0 更新本文件 + `docs/api/openapi/*.yaml` 后，业务流才能引用。
4. 数据库迁移号段由 S0 统一分配，见第 2 节。

### 0.2 契约占位锁定通知（已确认）

- 业务流**不得编辑** `aps-web/src/main/java/com/hengce/aps/web/controller/contract/**`。
- 业务流**不得编辑** `docs/api/openapi/*.yaml`。
- 上述目录/文件仅由 S0 维护；字段变更走契约变更单。
- 已实现的业务 Controller（如 `OrderController`、`SolveTaskController`）由对应业务流维护，但必须与本文件及静态 YAML 保持一致。

### 0.3 前端分工（已确认）

| 角色 | 范围 |
|---|---|
| FE1 | `aps-frontend/src/views/{orders,gantt}`、`aps-frontend/src/components/gantt`、`router/api/stores/types` 基座 |
| FE2 | `aps-frontend/src/views/{material,bom,resource,calendar,routing}` 与各自的 `src/api/<domain>.ts` |

- `src/types/api.ts`、`src/api/http.ts`、`src/api/index.ts`、路由与全局 store 属于基座，由 FE1 维护；FE2 只新增/修改各自 domain 的 api 文件，跨域类型变更提给 FE1。
- 后端契约字段变更仍由 S0 发单，前端不得先行改类型。

### 0.4 版本决策（已确认）

- Timefold 以骨架现有 **2.8.0** 为准；PRD 中「1.35+」视为最低要求，不回退、不升级大版本。
- MyBatis-Plus 选 **3.5.9**：该版本按 Spring Boot 3.3.4 构建，是 3.3 线最后一个官方对齐版本；3.5.10+ 已转向 Boot 3.4/3.5。
- Testcontainers 选 **1.21.4**（1.x 最新线）；2.x 为破坏性大版本，M1 不跟随。
- Spring Boot 3.3.13 已管理 actuator / validation 版本，根 pom 的 `dependencyManagement` 仅做显式锁定，不改插件配置。

---

## 1. 冻结结论速览

| 编号 | 结论 | 状态 |
|---|---|---|
| C1 | 保留现有 `SolveRequest/SolveResult` 作为 HTTP 过渡信封；求解内部改用中立 DTO `ScheduleProblem/ScheduleSolution/TaskAssignment/SolveConfig/SolveMetrics`，映射见 3 节。**C20 起 `POST /solve-tasks` 改为单一返回 `SolveTaskDetail`，`SolveResult` 信封与 `SUCCESS` 一并废弃** | 已确认（C20 修订） |
| C2 | 产能/工时按 `capacity_mode` 三式换算，`batch_capacity` 空按 1 兜底，三种模式均追加 `setup_seconds` | **默认假设，待产品最终确认** |
| C3 | `task.operation_index = routing_node.seq_no`；M1 不加 `task.routing_node_id` | 已确认 |
| C4 | `GET /api/v1/gantt/resource` 正式归求解流（S3）实现 | 已确认 |
| C5 | 默认场景 `sandboxId=00000000-0000-0000-0000-000000000401`；`tenantId` 由应用层注入；`solve_task.strategy_id` 允许空 | 已确认 |
| C6 | 路径 ID：materials/orders/resources 用 code，boms/routings/calendars 用 UUID，task/solve-task 用 bigint | 已确认 |
| C7 | 统一返回体、错误码、时间格式沿用既有实现，不另造 | 已确认 |
| C8 | 订单确认校验：产品存在、BOM 唯一 ACTIVE、routing 唯一 ACTIVE；客户黑名单 M1 跳过 | 已确认 |
| C9 | 枚举字典统一从 schema CHECK 导出，见 `docs/api/enums.md` | 已确认 |
| C10 | 列表筛选增量：materials `code/name` OR、boms `parentMaterialCode/status`、resources 与 calendars 同类筛选补齐 | 已确认 |
| C11 | 单条详情增量：新增 `GET /boms/{bomId}`，并补齐 resources/calendars 单条详情 | 已确认 |
| C12 | 求解问题能力补全：资源可用时段、工序依赖与滞后、换型矩阵；辅助资源与冻结任务语义补注 | 已确认（矩阵 M1 降级） |
| C13 | 订单更新与动作端点定稿：保留 `PUT /orders/{orderNo}`、`PUT /confirm`、`PUT /freeze`，解冻走 body，不设独立 `/unfreeze` | 已确认 |
| C14 | 资源与日历契约补全：详情/更新/停用端点、资源响应暴露 id、calendarVersion 改 INTEGER、不引入 status、可用时段 M1 不涉及 | 已确认 |
| C15 | 工艺路线图/工序模版端点、routings 筛选参数、路由节点资源引用统一 `primaryResourceId`（UUID）；`overrides` 三态（缺键 / 显式 `null` / UUID），见 20.3.1 | 已确认 |
| C16 | 工序模版详情统一 `{id}`（UUID），禁止 `{code}` 详情路径；新增 `process` OpenAPI 分组 | 已确认 |
| C17 | 登记 `DELETE /boms/{bomId}`、`DELETE /calendars/{id}`；同步已实现端点的 `x-implementation-status` | 已确认 |
| C19 | M2 策略规则与受控 DSL：8 条软约束数组 + `constraint:` 兼容别名，见 24.1 | 已确认（M2） |
| C20 | M2 异步求解生命周期、取消与 STOMP 进度，见 24.2 | 已确认（M2） |
| C21 | M2 增量重排与 `lockType/pinnedWindow`，见 24.3 | 已确认（M2） |
| C22 | M2 甘特人工调整（MOVE/LOCK/FREEZE/UNFREEZE）与撤销重做，见 24.4 | 已确认（M2） |
| C23 | M2 订单优先级、四态冻结与释放/开工自动冻结，见 24.5 | 已确认（M2） |
| C24 | M2 计划版本发布/回滚与快照，见 24.6 | 已确认（M2） |
| C25 | M2 库存落表 + `PUT /inventory` + MRP 齐套状态，见 24.7 | 已确认（M2） |
| C26 | M2 Excel 导入导出通用组件，见 24.8 | 已确认（M2） |
| C27 | M2 REST 认证分级、限流、幂等与新增错误码，见 24.9 | 已确认（M2） |
| C28 | M2 引擎路由与超时/异常降级标记，见 24.10 | 已确认（M2） |
| C29 | M2 订单甘特读接口，见 24.11 | 已确认（M2） |

---

## 2. 数据库迁移号段（已确认）

**M1 默认零迁移。** 所有 M1 需求优先在 `V1__init.sql` 已有结构内实现；不得修改 V1。

| 号段 | 归属流 | 说明 |
|---|---|---|
| V2–V3 | 主数据流 | 物料、BOM、资源、日历、客户、供应商 |
| V4–V5 | 工艺订单流 | 工序模板、工艺路线、订单 |
| V6–V7 | 求解流 | 求解任务、排产任务、场景、甘特 |
| V8–V9 | 测试部署流 | 仅限测试夹具、索引/约束修正 |

规则：

1. 只有 S0 发号；业务流不得自行新增 `V*.sql`。
2. 申请时说明「流 / 目的 / 是否改列 / 是否回填」，S0 分配具体号段。
3. 迁移文件命名 `V{n}__{snake_case_description}.sql`，放在 `db/migration/`。
4. 禁止改 `db/migration/V1__init.sql`（含注释、种子数据、CHECK 约束）。
5. 号段用尽或需要跨流共享时，由 S0 重新分配，不得抢占他流号段。
6. `aps-admin` 会把 `db/migration` 原样打进 classpath（见 `aps-admin/pom.xml` 的 resources 配置），迁移文件不要移动目录。
7. **任何已应用过的迁移文件连注释都不许改**：Flyway 校验和包含注释与空行，改一个字就会让共享库启动失败（V3 曾因此触发 `checksum mismatch`，V5 的 `locked_to` 注释即属此类）。校验和不一致一律由 S0 执行 `flyway repair`；业务流不得私改历史文件。**需要修注释也必须通过新迁移**（如 `COMMENT ON COLUMN task.locked_to IS '...'`）实现，绝不改本体。

### 2.1 M2 号段重新分配（D0 已发号）

M1 实际零迁移，上表 V2–V9 属 M1 阶段的预留，未使用。M2 重新发号如下，**每段唯一写者**，禁止跨段抢号：

| 版本 | 唯一写者 | 表/字段 | 应用时点 |
|---|---|---|---|
| V2 | W6 `dev-MRP` | `inventory`、`inventory_transit` | D3（W1 统一应用） |
| V3 | W5 `dev-BD5` | `plan_version`、`task.plan_version_id` | D3 |
| V4 | W2 `dev-SE1` | `constraint_dsl` | D3 |
| V5 | W4 `dev-SE4` | `task.lock_type/locked_from/locked_to/locked_resource_ids` | D3 |
| V6 | W3 `dev-SE3` | `solve_task` 幂等唯一索引（不加列） | D3 |

约束：

1. 五份迁移由各流在 D2 18:00 前交齐；**只有 W1 在 D3 按 V2→V6 顺序应用**，业务流不得自行 `flyway migrate`。
2. 已应用的 V2–V6 不得再修改；修订走新号段。
3. `strategy_rule` 已在 V1（677-736），M2 不新增策略表。
4. `setup_matrix_entry` **不建表**：M2 继续静态矩阵 + `setupMatrixFallback=true`，不占 V7。
5. `agent_event`/`agent_decision` 属 M3，不占 M2 号段。

---

## 3. C1 求解中立 DTO（已确认）

### 3.1 结论

- **HTTP 层**：`SolveRequest(taskNo,engine,orderNos,parameters)` 请求字段保留；响应信封自 C20 起统一为 `SolveTaskDetail`，`SolveResult(taskNo,status,tasks,message)` 不再返回。
- **求解层**：`SolverAdapter` 改为接收/返回中立 DTO，Timefold/OR-Tools 不得把引擎专有类型（`HardSoftScore`、`@PlanningEntity` 等）泄漏到 `solver-api`。
- **S0 不写实现**：本节只冻结字段与方法签名；落地由求解流（S2/S3）完成。

依据：PRD 4.5「排产引擎模块」输入/输出定义、PRD 3.1 主排产流程；`aps-solver/solver-api` 目标包 `com.hengce.aps.solver.api`。

### 3.2 五个中立 DTO（Java 21 record）

```java
package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 求解输入。tenantId/sandboxId 必填；solveTaskId 可空（试算场景）。 */
public record ScheduleProblem(
        String tenantId,
        UUID sandboxId,
        Long solveTaskId,
        UUID strategyId,                       // 可空
        String direction,                      // FORWARD/BACKWARD/BOTTLENECK
        List<SchedulingOrder> orders,
        List<SchedulingResource> resources,
        List<TaskAssignment> frozenAssignments,
        List<ConstraintSpec> constraints
) {
}

/** 订单快照；operations 为该订单生效工艺路线的工序，operationIndex = routing_node.seq_no。 */
public record SchedulingOrder(
        UUID orderId,
        String orderNo,
        String materialCode,
        BigDecimal qty,
        LocalDate dueDate,
        String priority,                       // URGENT/HIGH/NORMAL/LOW
        String freezeStatus,                   // UNFROZEN/FROZEN/LOCKED_TIME_WINDOW/LOCKED_RESOURCE
        UUID routingId,
        List<SchedulingOperation> operations
) {
}

/** 工序快照；standardSeconds/setupSeconds 已按 C2 的覆盖优先级取值。 */
public record SchedulingOperation(
        int operationIndex,
        String operationCode,
        String operationName,
        UUID operationTemplateId,
        BigDecimal standardSeconds,
        BigDecimal setupSeconds,
        UUID primaryResourceId,
        UUID primaryResourceGroupId,
        List<UUID> secondaryResourceIds,
        boolean optional
) {
}

/** 资源快照；字段与 resource 表列一一对应。 */
public record SchedulingResource(
        UUID resourceId,
        String code,
        String type,                           // PRIMARY/SECONDARY/HUMAN/TOOL
        UUID groupId,
        String capacityMode,                   // UNIT_PER_HOUR/SECONDS_PER_UNIT/BATCH_SECONDS
        BigDecimal capacityValue,
        Integer batchCapacity,                 // 可空；C2 按 1 兜底
        BigDecimal efficiency,
        UUID calendarId,
        String status                          // ACTIVE/INACTIVE（见 enums.md）
) {
}

/** 约束快照；M1 只透传 DSL 加载结果，不在求解器内解释规则语义。 */
public record ConstraintSpec(
        String code,
        String type,                           // HARD/SOFT
        BigDecimal weight,
        boolean hard,
        String phase,                          // INITIAL/OPTIMIZATION
        Map<String, Object> params
) {
}

/** 排产分配结果，也是 Timefold 计划实体的中立投影。 */
public record TaskAssignment(
        Long taskId,                           // 新解为空，持久化后回填
        UUID orderId,
        String orderNo,
        int operationIndex,
        String operationName,
        UUID operationTemplateId,
        UUID resourceId,
        String resourceCode,
        List<UUID> secondaryResourceIds,
        OffsetDateTime setupStartTime,
        OffsetDateTime setupEndTime,
        OffsetDateTime startTime,              // 加工开始 = setupEndTime
        OffsetDateTime endTime,                // 加工结束
        BigDecimal qty,
        Integer sequenceInResource,
        String status,                         // PLANNED/FROZEN/IN_PROGRESS/COMPLETED
        boolean frozen,
        Integer version
) {
}

/** 求解输出。score 为引擎无关的硬/软分投影；METRICS 见下。 */
public record ScheduleSolution(
        String status,                         // SOLVED/INFEASIBLE/TIMEOUT/FAILED
        ScoreFact score,
        List<TaskAssignment> assignments,
        List<ConstraintJustification> justifications,
        SolveMetrics metrics,
        String message
) {
}

/** 引擎无关评分：硬分必须为 0 才可发布。 */
public record ScoreFact(
        BigDecimal hardScore,
        BigDecimal softScore,
        String scoreText
) {
}

/** 约束归因（用于 Agent/甘特解释），taskIds 指向 TaskAssignment.taskId。 */
public record ConstraintJustification(
        String constraintCode,
        String type,                           // HARD/SOFT
        List<Long> taskIds,
        String description
) {
}
```

> M1 求解流还需在 `SchedulingResource` / `SchedulingOperation` / `ScheduleProblem` 上补 C12 字段
> （`availabilityWindows`、`predecessors`、`setupMatrix`）；本节其余字段与签名保持不变。

### 3.3 求解配置与指标

```java
package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 算法参数；与具体引擎无关。 */
public record SolveConfig(
        String engine,                         // timefold/ortools/rule_based（外部小写，见 C9）
        int timeoutSeconds,                    // 默认 60
        int moveThreadCount,                   // 0 = CPU-1，最小 1
        String acceptor,                       // 默认 LATE_ACCEPTANCE
        long randomSeed,
        long maximumStepCount,                 // <=0 表示不限
        int unimprovedSecondsSpentLimit,       // 默认 10
        String direction,                      // FORWARD/BACKWARD/BOTTLENECK
        boolean useFrozenAssignments,          // 默认 true
        Map<String, Object> parameters         // 请求 parameters 原样透传
) {
}

/** 求解度量，对应 solve_task.metrics JSONB。 */
public record SolveMetrics(
        long durationMs,
        long stepCount,
        int unimprovedStepCount,
        String terminationReason,              // TIME_LIMIT/STEP_LIMIT/UNIMPROVED_LIMIT/TERMINATED
        List<ScorePoint> scoreHistory,
        int assignedTaskCount,
        Map<String, Object> extra
) {
}

/** 评分轨迹采样点。 */
public record ScorePoint(
        long elapsedMs,
        long step,
        BigDecimal hardScore,
        BigDecimal softScore
) {
}
```

### 3.4 适配器方法签名

```java
public interface SolverAdapter {

    SolverEngine engine();

    /** 同步求解；异步生命周期由 aps-service 的求解任务调度负责，不在适配器内。 */
    ScheduleSolution solve(ScheduleProblem problem, SolveConfig config);
}
```

约束：

- `SolverAdapter` 在 M1 只保留上述一个 `solve` 方法，不保留 `solve(SolveRequest)` 重载，避免双入口。
- `TaskAssignment` 的 Timefold 注解（`@PlanningEntity`、`@PlanningVariable`）只能放在 `solver-timefold` 的包装类/子类中，禁止进入 `solver-api`。
- `OffsetDateTime` 一律 UTC（`ZoneOffset.UTC`）；`LocalDate` 表示交期日，不带时区。
- 禁止在 `solver-api` 引入 Spring、Jackson、Timefold、OR-Tools 依赖。

### 3.5 与现有 `SolveRequest/SolveResult` 的映射（C1 历史归档）

| 现有字段 | 目标字段 | 映射规则 |
|---|---|---|
| `SolveRequest.taskNo` | `ScheduleProblem.solveTaskId` | `taskNo` 仅作客户端关联号，**不是主键**；服务端按 `solve_task.id` 关联，响应回显 `taskNo` |
| `SolveRequest.engine` | `SolveConfig.engine` | 小写 code 归一化；空值 `timefold`；DB 存大写 |
| `SolveRequest.orderNos` | `ScheduleProblem.orders` | 按 `tenant_id + order_no` 查询；空表示该场景全部 `CONFIRMED` 订单 |
| `SolveRequest.parameters` | `SolveConfig.parameters` | 原样透传；已知键 `timeoutSeconds/moveThreadCount/acceptor/randomSeed` 提升为强类型字段 |
| `SolveResult.status=SUCCESS` | `ScheduleSolution.status=SOLVED` | **已废弃**：C20 起 DB 与 API 统一六值枚举，`POST /solve-tasks` 不再返回 `SolveResult` |
| `SolveResult.status=FAILED` | `ScheduleSolution.status=FAILED` | 回包写 `FAILED` |
| `SolveResult.status=UNSUPPORTED` | `ScheduleSolution.status=FAILED` | 引擎不支持属参数错误，M1 目标改回 42200；过渡期保留 |
| `SolveResult.tasks[]` | `ScheduleSolution.assignments[]` | 只投影 `orderNo/resourceCode/startTime/endTime`；完整字段走甘特读接口 |
| `SolveResult.message` | `ScheduleSolution.message` | 原样 |
| `ScheduledTask.startTime/endTime` | `TaskAssignment.startTime/endTime` | 语义为**加工窗口**；`setupStartTime/setupEndTime` 独立存放 |

迁移期要求：

- M1 的 `POST /api/v1/solve-tasks` 请求新增可选 `sandboxId`、`strategyId`，见 `docs/api/openapi/solve.yaml`；缺省 `sandboxId` 取 C5 默认值。
- **C20 起 `POST /solve-tasks` 一律返回 `SolveTaskDetail`**；本节 `SolveResult` 映射仅作为 M1 历史实现归档，不再是对外契约（`SUCCESS` 已废弃，见 24.2）。

### 3.6 受影响文件清单（由求解流 S2/S3 落地）

| 文件 | 改动 |
|---|---|
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolverAdapter.java` | 方法签名替换 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/ScheduleProblem.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/ScheduleSolution.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/TaskAssignment.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolveConfig.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolveMetrics.java` | 新增 |
| `aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldSolverAdapter.java` | 适配新输入/输出 |
| `aps-solver/solver-ortools/src/main/java/com/hengce/aps/solver/ortools/OrtoolsSolverAdapter.java` | 同步签名（可先抛不支持） |
| `aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java` | 组装问题、映射结果 |
| `aps-web/src/main/java/com/hengce/aps/web/controller/SolveTaskController.java` | 请求/响应适配 |
| `aps-frontend/src/types/api.ts` | `sandboxId/strategyId` 可选字段与任务时间类型 |
| `docs/api/openapi/solve.yaml` | S0 已补 `sandboxId/strategyId`；后续字段变更走变更单 |

---

## 4. C2 时长/产能换算公式（**默认假设，待产品最终确认**）

### 4.1 输入

| 输入 | 来源列 | 说明 |
|---|---|---|
| `capacity_mode` | `resource.capacity_mode` | `UNIT_PER_HOUR` / `SECONDS_PER_UNIT` / `BATCH_SECONDS` |
| `capacity_value` | `resource.capacity_value` | `NUMERIC(18,4)`，单位随模式变化 |
| `batch_capacity` | `resource.batch_capacity` | 可空；空按 **1** 兜底 |
| `efficiency` | `resource.efficiency` | `NUMERIC(5,2)`，默认 1.00；范围 0.5–1.5 |
| `standard_seconds` | `routing_node.standard_seconds` 优先，否则 `operation_template.standard_seconds` | 单件标准工时（秒） |
| `setup_seconds` | `routing_node.setup_seconds` 优先，否则 `operation_template.setup_seconds` | 换型/准备工时（秒） |
| `qty` | `orders.qty` / `task.qty` | 订单数量 |

依据：`db/migration/V1__init.sql` 的 `resource`、`operation_template`、`routing_node`、`orders` 列定义；PRD 4.1 资源产能、4.2 工序模板。

### 4.2 三种模式

设 `q = qty`、`cap = capacity_value`、`eff = efficiency`（空按 1.00）、`batch = batch_capacity`（空按 1）。

| 模式 | 有效产能/单件工时 | 加工时长 `T_run`（秒） |
|---|---|---|
| `UNIT_PER_HOUR` | 有效产能 `C = cap × eff`（单位/小时）；单件工时 = `60 / C`（分钟） | `T_run = q × 3600 / (cap × eff)` |
| `SECONDS_PER_UNIT` | 单件工时 = `standard_seconds / eff`（秒）；`capacity_value` 不参与 | `T_run = q × standard_seconds / eff` |
| `BATCH_SECONDS` | 批次数 `n = ceil(q / batch)`；每批工时 `cap / eff`（秒） | `T_run = n × cap / eff` |

总时长：

```text
T_total = setup_seconds + T_run
```

### 4.3 舍入与边界（默认假设）

1. `T_run` 与 `T_total` 均**向上取整到整秒**（CEILING），避免低估占用导致资源超载。
2. `efficiency` 为空按 `1.00`；`batch_capacity` 为空按 `1`；`setup_seconds` 为空按 `0`。
3. `UNIT_PER_HOUR` 且 `cap × eff <= 0`、`SECONDS_PER_UNIT` 且 `standard_seconds <= 0`、`BATCH_SECONDS` 且 `cap <= 0` → 返回 `42200`，不得静默排 0 工时。
4. 三种模式都**追加** `setup_seconds`；换型时间不计入 `T_run`，在甘特中单独体现为 `setupStartTime/setupEndTime`。
5. 甘特时间窗口约定：
   - `setupStartTime = 占用块开始`
   - `setupEndTime = setupStartTime + setup_seconds`
   - `startTime = setupEndTime`（加工开始）
   - `endTime = startTime + T_run`（加工结束）
   - `setup_seconds = 0` 时，`setupStartTime = setupEndTime = startTime`（不允许为空）。
6. 换型矩阵（setup matrix）不在 C2 范围；M1 使用静态 `setup_seconds`，矩阵寻优留待后续里程碑。
7. **待产品确认点**：`efficiency` 在 `UNIT_PER_HOUR` 下乘产能、在 `SECONDS_PER_UNIT` 下除工时，两种口径互为倒数；请产品确认业务含义一致。

---

## 5. C3 任务粒度（已确认）

1. `task.operation_index` 直接等于 `routing_node.seq_no`，从 0 或 1 开始均允许，按路线实际值原样存储，不做重编号。
2. M1 **不新增** `task.routing_node_id`；关联方式：
   - `task.order_id -> orders.id`
   - `orders.routing_id` 为空时取该产品唯一 `ACTIVE` routing
   - `routing_node(routing_id, seq_no = task.operation_index)`
3. 业务唯一键约定：`(tenant_id, sandbox_id, order_id, operation_index)` 唯一；M1 不新增唯一索引（零迁移），由服务层保证。
4. 同订单同工序允许拆分/合并时，`task_adjustment.adjust_type` 记录 `SPLIT/MERGE`，`task` 仍以 `operation_index` 标识工序归属；不引入工序实例 ID。
5. `task.sequence_in_resource` 由求解/调整按 `sandbox_id + resource_id` 内的时间顺序生成并持久化，用于甘特稳定排序。

依据：`V1__init.sql` 的 `task.operation_index`、`routing_node.seq_no`、`task_adjustment`；PRD 4.2 工艺路线、3.5 人工干预。

---

## 6. C4 甘特读接口（已确认）

### 6.1 归属

`GET /api/v1/gantt/resource` 正式归**求解流（S3）**实现，不是无人认领；`gantt.yaml` 已加 `x-owner: solve-flow`。

### 6.2 请求

```http
GET /api/v1/gantt/resource?sandboxId={uuid}&from={iso8601}&to={iso8601}&resourceCodes={code1,code2}
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `sandboxId` | query | 否 | 缺省 `00000000-0000-0000-0000-000000000401`（C5） |
| `from` | query | 是 | ISO-8601 UTC，闭区间起点 |
| `to` | query | 是 | ISO-8601 UTC，开区间终点；`from < to` |
| `resourceCodes` | query | 否 | 逗号分隔；空表示全部 |

### 6.3 响应 `data`

```json
{
  "sandboxId": "00000000-0000-0000-0000-000000000401",
  "from": "2026-01-01T00:00:00Z",
  "to": "2026-01-08T00:00:00Z",
  "resources": [
    {
      "resourceCode": "R-001",
      "resourceName": "CNC-01",
      "resourceType": "PRIMARY",
      "calendarId": "5f0c2e1a-0000-4000-8000-000000000301",
      "calendarBound": true,
      "tasks": [
        {
          "id": 1001,
          "orderNo": "SO-2026-001",
          "operationIndex": 10,
          "operationName": "粗车",
          "resourceCode": "R-001",
          "secondaryResourceCodes": ["R-009"],
          "setupStartTime": "2026-01-02T00:00:00Z",
          "setupEndTime": "2026-01-02T00:30:00Z",
          "startTime": "2026-01-02T00:30:00Z",
          "endTime": "2026-01-02T04:30:00Z",
          "qty": 120,
          "status": "PLANNED",
          "sequenceInResource": 1,
          "version": 3
        }
      ]
    }
  ]
}
```

字段规则：

- 顶层 `Result` 包装：`{code,message,data,traceId}`，见 C7。
- `resources[]`：按 `resource.code` 升序；`resourceType` 取 `resource.type`。
- **`calendarId`（C4 增量，可空）**：该行资源生效日历的 `calendar.id`，解析优先级 **资源专属 > 资源组 > 全厂默认**（同 `V1__init.sql` `resource.calendar_id` 注释）。未绑定任何日历 → `null`，前端直接按全厂默认日历兜底，**不得再对每行调 `GET /calendars/{id}`**（避免 N+1，且不会撞 `/resources` 分页上限）。
- **`calendarBound`（C4 增量，摘要）**：`true` 表示存在资源专属或资源组日历；`calendarId=null` 时固定 `false`。前端可用它区分"已绑定但为空"与"未绑定"。
- `calendarId/calendarBound` 均为只读投影，不参与排序、不改变 C4 的窗口过滤语义。
- `tasks[]`：按 `sequenceInResource` 升序，空值按 `startTime` 兜底；同资源内稳定。
- 时间窗口：返回与 `[from, to)` **有交集**的任务；`setupStartTime` 可早于 `from`。
- `startTime/endTime` 是加工窗口，`setupStartTime/setupEndTime` 是换型窗口；`endTime > startTime`，`setupEndTime = startTime`。
- `secondaryResourceCodes` 为空数组而不是 `null`。
- 非分页结构；不返回 `total`。
- 只读接口，不需要 `Idempotency-Key`。

依据：PRD 4.7 排产结果与甘特图、3.5 人工干预、9.1 接口清单；`docs/api/openapi/gantt.yaml`。

---

## 7. C5 默认场景/租户（已确认）

| 项 | 值 | 依据 |
|---|---|---|
| 默认 `sandboxId` | `00000000-0000-0000-0000-000000000401` | `V1__init.sql` 默认 BASELINE 场景 |
| 默认租户 | `tenantId = 00000000-0000-0000-0000-000000000001`（code `DEMO`） | `V1__init.sql` 默认租户 |
| 租户注入 | 由应用层（过滤器/拦截器）从认证上下文注入，Controller/Service 不信任请求体 `tenantId` | PRD 2.6 多租户、5.4 安全 |
| `solve_task.strategy_id` | 允许 NULL；为空表示使用默认策略 | `V1__init.sql` `solve_task.strategy_id` 可空 |
| 请求缺省 | 求解/甘特请求未传 `sandboxId` 时一律落到 401；显式传入则校验存在且属于当前租户 | 本节 |

补充：

- 所有业务表写入必须带 `tenant_id`；跨租户查询直接 `404`，不泄露存在性。
- `sandbox` 的 `BASELINE` 每租户唯一（`uq_sandbox_baseline`）；`WHAT_IF` 从 BASELINE 复制。
- `solve_task.status` 只允许 `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`，见 `docs/api/enums.md`。

---

## 8. C6 路径 ID 语义（已确认）

| 资源 | 路径 ID | 类型 | 示例 |
|---|---|---|---|
| 物料 | `code` | `VARCHAR(64)` | `/api/v1/materials/M-001` |
| 订单 | `orderNo` | `VARCHAR(64)` | `/api/v1/orders/SO-2026-001` |
| 资源 | `code` | `VARCHAR(64)` | `/api/v1/resources/R-001/maintenance` |
| BOM | `id` | UUID | `/api/v1/boms/{uuid}/tree` |
| 工艺路线 | `id` | UUID | `/api/v1/routings/{uuid}/graph` |
| 日历 | `id` | UUID | `/api/v1/calendars/{uuid}/available` |
| 排产任务 | `id` | `BIGSERIAL` (`int64`) | `/api/v1/tasks/1001/adjust` |
| 求解任务 | `id` | `BIGSERIAL` (`int64`) | `/api/v1/solve-tasks/9001` |
| 策略/沙箱/计划 | `id` | UUID | `/api/v1/sandboxes/{uuid}/compare` |

修正原则：

1. 路径变量名与语义一致：`{code}` / `{orderNo}` / `{id}`，不再用 `{id}` 承载 code。
2. Controller 的 `@PathVariable` 类型必须与 YAML 一致：UUID→`UUID`，bigint→`Long`，code→`String`。
3. 已实现端点（`OrderController` 的 `{orderNo}`）保持不动；占位端点向它对齐。
4. 本文件、`controller/contract/**`、`docs/api/openapi/*.yaml` 三处必须一致；本轮已修正的文件见第 12 节。
5. 资源对外用 `code` 寻址，但响应体必须带 `id`（UUID），供 `calendar.resourceId` 等引用绑定；`id` 不作为路径参数（见 C14）。

---

## 9. C7 返回体 / 错误码 / 时间格式（已确认）

沿用既有实现，不重复造：

| 项 | 契约 | 实现 |
|---|---|---|
| 成功 | `code=0, message="success", data, traceId` | `aps-common` `Result<T>` |
| 失败 | `code` 为 4xxxx/5xxxx，`data=null`，`message` 不暴露堆栈 | `ErrorCode` + `RestExceptionHandler` |
| 常用错误码 | `40000` 参数、`40400` 不存在、`40900/40901` 冲突、`42200` 业务校验、`42900` 限流、`50000` 内部、`50010` 求解不可用、`50100` 占位未实现、`50300` 依赖不可用 | `ErrorCode` |
| HTTP 映射 | 业务码 → HTTP 状态由 `RestExceptionHandler.STATUS_MAPPING` 统一决定 | `RestExceptionHandler` |
| traceId | 响应头 `X-Trace-Id` 与响应体 `traceId` 一致 | `TraceIdFilter`、`TraceContext` |
| 时间 | 传输层 ISO-8601 UTC（`Z`）；DB `timestamptz`；Java `OffsetDateTime` | `conventions.md` |
| 分页 | `page`（从 1 开始）、`size`、`sort`；出参 `items,total,page,size` | `PageQuery/PageResult` |
| 幂等 | 写接口接受 `Idempotency-Key`（8–128 位） | `conventions.md` |

M1 必须修正的已知偏差：现有 `SolveResult.tasks[].startTime/endTime` 为无偏移本地时间，M1 改为 UTC（`ScheduledTask`/`TaskAssignment` 均用 `OffsetDateTime`）。

---

## 10. C8 订单确认校验清单（已确认）

`PUT /api/v1/orders/{orderNo}/confirm` 执行顺序：

| 序 | 校验 | 规则 | 失败码 |
|---|---|---|---|
| 1 | 订单存在且属于当前租户 | `orders.order_no` 查询；已删除→404 | `40400` |
| 2 | 状态可确认 | 仅 `DRAFT` 可转 `CONFIRMED`；重复确认幂等返回 200 | `40901` |
| 3 | 产品存在 | `orders.product_id -> material.id`，且 `material.deleted=false` | `42200` |
| 4 | BOM 唯一 ACTIVE | `bom.parent_material_id = orders.product_id` 且 `status='ACTIVE'`，恰好 1 条（`uq_bom_one_active_per_parent`） | `42200` |
| 5 | Routing 唯一 ACTIVE | 若 `orders.routing_id` 非空，校验该 routing `ACTIVE`；否则按产品取唯一 `ACTIVE` routing（`uq_routing_one_active_per_product`） | `42200` |
| 6 | 数量/交期 | `qty > 0`、`due_date` 非空（基础校验） | `40000` |
| 7 | 客户黑名单 | **M1 跳过**；`customer_id` 允许为空 | — |

补充：

- 第 4/5 条若命中多条 ACTIVE，按数据不一致处理（`42200`），不得静默取第一条。
- 确认成功后写审计；不自动触发求解。
- 客户黑名单（FR-MD-005）M1 不实现，已由项目负责人确认；后续里程碑再加。

依据：PRD 4.3 订单管理（FR-OM 订单确认）、4.1 基础数据；`V1__init.sql` 的 `orders`、`bom`、`routing` 唯一索引。

---

## 11. C9 枚举字典（已确认）

完整表格与 JSON 见 [enums.md](./enums.md)。约定：

- API 传输值使用 schema CHECK 的大写字符串，前端常量与后端枚举共用同一份字典。
- 唯一例外：求解引擎外部 code 使用小写 `timefold|ortools|rule_based`（兼容现有 `POST /solve-tasks`），落库转大写。
- 新增枚举值必须同时更新 `docs/api/enums.md` 与 schema（需迁移时走第 2 节号段）。

---

## 12. 本轮契约对齐改动（S0）

| 文件 | 改动 |
|---|---|
| `aps-web/.../controller/contract/MaterialsContractController.java` | `/materials/{id}` → `/materials/{code}`；`/resources/{id}/maintenance` → `/resources/{code}`；UUID 路径参数改为 `UUID` |
| `aps-web/.../controller/contract/OrdersContractController.java` | `/orders/{id}/confirm|freeze` → `/orders/{orderNo}`；策略/沙箱 UUID 参数改为 `UUID` |
| `aps-web/.../controller/contract/SolveContractController.java` | `solve-tasks/{id}`、`agent/explain/{taskId}` 改为 `Long` |
| `aps-web/.../controller/contract/GanttContractController.java` | `/tasks/{id}/adjust` 改为 `Long`；计划 UUID 参数改为 `UUID` |
| `docs/api/openapi/materials.yaml` | materials/resources 路径改 `{code}` |
| `docs/api/openapi/orders.yaml` | confirm/freeze 路径改 `{orderNo}` |
| `docs/api/openapi/solve.yaml` | solve-task 路径参数改 `int64`；`SolveSubmitRequest` 增补可选 `sandboxId/strategyId` |
| `docs/api/openapi/gantt.yaml` | 资源甘特标注 `x-owner: solve-flow`；`sandboxId` 改为可选并注明默认值 |

---

## 13. 各流开工前置

| 流 | 开工前必须确认 |
|---|---|
| 主数据流 | 只读本文件 + `enums.md`；不新增迁移；物料/资源路径用 code；UUID 资源路径用 UUID；BOM/日历/工艺的 ACTIVE 唯一规则复用 C8 |
| 工艺订单流 | `task.operation_index = routing_node.seq_no`（C3）；订单确认按 C8；`orders.status` 严格按枚举流转；不改 contract |
| 求解流（S2/S3） | 按 C1 落地中立 DTO 与适配器签名；按 C2 公式计算工时（默认假设）；实现 `GET /gantt/resource`（C4）；默认 sandbox 401（C5） |
| 测试部署流 | 仅使用 V8–V9；测试数据不得写进 V1；Testcontainers 版本由根 pom 管理；不要新增部署文件 |
| 前端 FE1/FE2 | 按 0.3 分工；类型只改各自范围；求解响应新增字段可选兼容；甘特字段以 C4 为准 |

---

## 15. C10 列表筛选参数（增量，已确认）

纯增量：不改已有参数名、不改响应结构；新参数未传时行为与之前一致。

| 端点 | 参数 | 类型 | 语义 |
|---|---|---|---|
| `GET /api/v1/materials` | `code` | string(64) | 模糊匹配（contains，忽略大小写）；与 `name` **OR** |
| `GET /api/v1/materials` | `name` | string(128) | 模糊匹配（contains，忽略大小写）；与 `code` **OR** |
| `GET /api/v1/materials` | `type` | enum `MaterialType` | 精确匹配；与其他条件 **AND**（已有参数） |
| `GET /api/v1/materials` | `categoryId` | UUID | 精确匹配；与其他条件 **AND**（新增） |
| `GET /api/v1/boms` | `parentMaterialCode` | string(64) | 精确匹配 `material.code`；与其他条件 **AND** |
| `GET /api/v1/boms` | `status` | enum `BomStatus` | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/resources` | `code` | string(64) | 模糊匹配（contains，忽略大小写）；与 `name` **OR** |
| `GET /api/v1/resources` | `name` | string(128) | 模糊匹配（contains，忽略大小写）；与 `code` **OR** |
| `GET /api/v1/resources` | `type` | enum `ResourceType` | 精确匹配；与其他条件 **AND**（已有参数） |
| `GET /api/v1/resources` | `groupId` | UUID | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `name` | string(128) | 模糊匹配（contains，忽略大小写） |
| `GET /api/v1/calendars` | `type` | enum `CalendarType` | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `resourceId` | UUID | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `resourceGroupId` | UUID | 精确匹配；与其他条件 **AND** |

**OR/AND 结论**：`code` 与 `name` 之间为 **OR**（前端把同一关键字同时传给两者时命中任一即可）；`code/name` 组合与 `type/categoryId/groupId` 等其他筛选条件之间为 **AND**。BOM 的 `parentMaterialCode` 与 `status` 均为精确匹配且相互 AND。

依据：`V1__init.sql` 的 `material.code/name/type/category_id`、`bom.parent_material_id/status`、`resource.code/name/type/group_id`、`calendar.name/type/resource_id/resource_group_id`。
消费方：FE2 物料/BOM/资源/日历列表页；实现方 BD-02（物料/资源/日历）、BD-03（BOM）。

---

## 16. C11 单条详情接口（增量，已确认）

| 端点 | 路径 ID | 响应 | 归属 | 消费方 |
|---|---|---|---|---|
| `GET /api/v1/boms/{bomId}` | BOM UUID | `ResultOfBom`（BOM 头 + 完整 `items`） | BD-03 | FE2 BOM 详情页 |
| `GET /api/v1/resources/{code}` | `resource.code` | `ResultOfResource` | BD-02 | FE2 资源详情/编辑 |
| `GET /api/v1/calendars/{id}` | calendar UUID | `ResultOfCalendar` | BD-02 | FE2 日历详情/编辑 |

约定：

1. 新端点均为 `x-implementation-status: placeholder`，未实现前返回 501/50100；实现后改为 `implemented`。
2. `GET /boms/{bomId}` 是 BOM `items` 的稳定来源；列表即使后续裁剪 `items`，详情页也不得依赖列表行。
3. 不改变现有 `/boms/{id}/tree`、`/boms/{id}/activate`、`/calendars/{id}/available`。
4. 错误码沿用 C7：不存在 404/40400，未实现 501/50100。

依据：FE-02 联调反馈；`materials.yaml` 现有 `Bom`/`Resource`/`Calendar` schema 已包含所需字段，无需新增响应模型。

---

## 17. C12 求解问题能力补全（M1，契约增量）

> 状态：17.1、17.2 为 M1 **必须**（当前 `solver-api` 缺失）；17.3 为 M1 **不必须**（降级到静态换型）；17.4、17.5 已具备，仅补语义。
> **M2 注记**：DSL 语法（`constraints[]` 数组、单数 `constraint:` 兼容别名、白名单 ruleCode）以 **C19** 为准；本节 `ConstraintSpec` 透传语义与冻结/换型语义不变，不重编号。
> 依据：SE-03 联调反馈、PRD 4.5（FR-SE-001/002）、PRD 4.1（FR-MD-003/004）、PRD 4.2（FR-PR-001），`V1__init.sql` 的 `routing_edge`、`calendar`、`resource`，`docs/db/schema.md` 风险 2。
> 边界：本节只定义契约层类型与语义；`solver-api` 代码由求解流按本节落地，S0 不改代码。

### 17.1 资源可用时段（缺失）

**现状**：`SchedulingResource` 只有 `calendarId`，没有展开后的可用窗口，无法承载班次、节假日、例外日期。**M1 必须。**

建议在 `SchedulingResource` 增加：

```java
List<AvailabilityWindow> availabilityWindows

public record AvailabilityWindow(
        OffsetDateTime start,   // UTC，闭区间
        OffsetDateTime end      // UTC，开区间
) {
}
```

语义：

1. 由服务层在组装问题前按 `resource.calendar_id -> calendar` 展开：工作时段 + 班次 - 节假日 + 例外日期，裁剪到求解时域 `[horizonStart, horizonEnd)`。
2. 列表按 `start` 升序、互不重叠，`end > start`；空列表表示 **7×24 全可用**。
3. 求解器必须保证任务占用块 `[setupStartTime, endTime)` 完整落在**同一个**可用窗口内；M1 不支持跨窗口续排（跨非工作时段）。
4. `calendarId` 为空、日历未配置、展开失败 → 一律按 7×24，并在 `SolveMetrics.extra` 标记 `calendarFallback=true`，不得静默当作有日历。

降级行为：缺少该字段时求解流只能按 7×24 处理，日历硬约束失效；需在结果 `message`/metrics 中显式标注，不能伪装成已满足日历。

### 17.2 工序依赖类型与滞后（缺失）

**现状**：`SchedulingOperation` 没有 `dependType`/`lagMinutes`，问题级也没有依赖边；SE-03 无法表达 SS/FF 与滞后。**M1 必须。**

建议在 `SchedulingOperation` 增加（依赖指向同一订单内的工序）：

```java
List<OperationDependency> predecessors

public record OperationDependency(
        int predecessorOperationIndex,  // 同订单内 SchedulingOperation.operationIndex
        String dependType,              // FS / SS / FF；SF 仅保留，M1 不支持
        int lagMinutes                  // M1 要求 >= 0
) {
}
```

约束式（`P` 为前序，`S` 为当前）：

| 类型 | 约束 |
|---|---|
| `FS` | `S.startTime >= P.endTime + lagMinutes` |
| `SS` | `S.startTime >= P.startTime + lagMinutes` |
| `FF` | `S.endTime >= P.endTime + lagMinutes` |
| `SF` | `S.endTime >= P.startTime + lagMinutes`（**保留**，V1 的 CHECK 不含 SF，M1 不下发） |

语义补充：

1. `V1__init.sql` 的 `chk_routing_edge_depend_type` 只允许 `FS/SS/FF`（第 549 行），`lag_minutes` 已存在（第 538 行）；因此 M1 有效取值只有 FS/SS/FF。
2. 若上游出现 `SF`，M1 必须拒绝该问题（返回 `FAILED` 并说明），不得静默降级成 FS；启用 SF 需要新迁移（M2/M3 号段，M1 零迁移）。
3. `lagMinutes < 0`（重叠/负滞后）M1 不支持；按 42200/FAILED 拒绝。
4. 路由环检测仍由应用层保存时完成；求解前若发现环，返回 `INFEASIBLE`/`FAILED`，不做隐式断环。
5. `predecessors` 为空表示无显式依赖；求解流按 `operationIndex` 升序补默认 FS、`lagMinutes=0`，保证工序串行。

降级行为：缺少该字段时只能做「按 `operationIndex` 串行 FS、零滞后」；SS/FF 与滞后全部失效，必须在结果中标明 `dependencyFallback=true`。

### 17.3 换型矩阵（缺失，M1 不必须）

**现状**：问题级没有 setup matrix；`setup_matrix_entry` 表不在 V1（`docs/db/schema.md` 第 487/494 行写明 M2/M3 迁移），M1 零迁移无法落库。**M1 不必须，降级可接受。**

为 M2/M3 预留问题级字段：

```java
List<SetupMatrixEntry> setupMatrix

public record SetupMatrixEntry(
        UUID resourceId,        // 可空：空表示适用于所有资源
        String keyType,         // OPERATION_CODE / MATERIAL_CODE
        String fromKey,         // 可空：空表示默认行
        String toKey,           // 可空：空表示默认列
        int setupSeconds        // 换型时长（秒），M1 约定整秒
) {
}
```

语义：

1. 仅当同一资源上相邻两个任务命中一条矩阵记录时覆盖 `setupSeconds`；未命中回退 `SchedulingOperation.setupSeconds`（C2 §4）。
2. 匹配优先级：指定 `resourceId` > 空 `resourceId`；`fromKey/toKey` 精确 > 空（通配）；同优先级冲突取较大 `setupSeconds`。
3. 矩阵时长替代静态 `setup_seconds`，仍按 C2 的甘特窗口规则体现 `setupStartTime/setupEndTime`，不并入 `T_run`。
4. M1 不要求该字段；`setupMatrix` 为空即全部使用静态 `setupSeconds`，这是**已确认可接受**的降级。
5. 若 M1 后产品坚持矩阵，需要新迁移（建议 V3 段，由 S0 发号），并同步本契约。

### 17.4 辅助资源（已具备，补语义）

- 字段已存在：`SchedulingOperation.secondaryResourceIds` 与 `TaskAssignment.secondaryResourceIds`（`List<UUID>`，空值归一化为空列表）。
- **M1 语义（冻结）**：辅助资源在加工窗口 `[startTime, endTime)` 内与主资源**同时占用（exclusive）**，同一辅助资源的占用区间不得与其他任务重叠；辅助资源自身的 `availabilityWindows` 同样生效。
- 换型窗口 `[setupStartTime, startTime)` **默认不占用**辅助资源；如某类工装需要在换型期占用，由约束 DSL 单独声明（M1 不内置）。
- 若后续业务改为「仅约束、可共享」，必须走契约变更单，不得由实现方自行改变语义。

### 17.5 冻结任务（已具备，字段一致）

- 字段已存在：`ScheduleProblem.frozenAssignments`，类型为 `List<TaskAssignment>`，与 `TaskAssignment` 字段完全一致。
- 已承载：`resourceId`/`resourceCode`（pinned 资源）、`setupStartTime/setupEndTime/startTime/endTime`（pinned 时间）、`frozen`。
- **M1 限制**：没有独立 `pinned` 字段；`LOCK`（时间窗内可微调）与 `FREEZE`（完全不可移动）统一按不可移动处理，以 `frozen=true` 表达。
- 若 M1 需要区分 LOCK 与 FREEZE，需新增 `lockType`/`pinnedWindow` 契约字段（走变更单）；当前按完全冻结处理是可接受的降级。

### 17.6 降级汇总（给求解流）

| 能力 | M1 是否必须 | 缺少/为空时的降级 | 必须标记 |
|---|---|---|---|
| 资源可用时段 | 是 | 7×24 全可用 | `metrics.extra.calendarFallback=true` |
| 工序依赖 | 是 | 按 `operationIndex` 串行 FS、lag=0 | `metrics.extra.dependencyFallback=true` |
| 换型矩阵 | 否（M1 不必须） | 回退 `SchedulingOperation.setupSeconds` | 无需标记 |
| 辅助资源 | 是（语义已具备） | 空列表 = 无辅助资源 | — |
| 冻结任务 | 是（字段已具备） | 空列表 = 无冻结；LOCK/FREEZE 均不可移动 | — |

---

## 18. C13 订单更新与动作端点（已确认）

> 依据：PRD 4.3（FR-OM-001~004）、`V1__init.sql` 的 `orders` 表（第 561–598 行）、`docs/api/openapi/orders.yaml`、C8。
> 消费方：FE1 订单页；实现方：订单流。S0 只冻结契约，不实现。

### 18.1 端点定稿

| 动作 | 方法与路径 | 请求体 | 成功响应 | 说明 |
|---|---|---|---|---|
| 更新订单 | `PUT /api/v1/orders/{orderNo}` | `OrderUpdateRequest` | `ResultOfOrder` | 仅 `DRAFT` 可改；`version` 乐观锁 |
| 确认订单 | `PUT /api/v1/orders/{orderNo}/confirm` | 无 | `ResultOfOrder` | `DRAFT → CONFIRMED`；幂等 |
| 冻结/解冻 | `PUT /api/v1/orders/{orderNo}/freeze` | `OrderFreezeRequest{freezeStatus,version}` | `ResultOfOrder` | `freezeStatus=UNFROZEN` 即解冻；**不设独立 `/unfreeze`** |

**最终采用**：保留 `PUT /confirm`、`PUT /freeze`（body 表达冻结/解冻），不提供 `POST /confirm`、`POST /freeze`、`POST /unfreeze`。

### 18.2 与任务卡差异及采纳理由

任务卡描述为 `POST /confirm`、`POST /freeze`、`POST /unfreeze`；本契约采纳 `PUT` 两段式：

1. 现有静态契约、占位 Controller 与 C8 均已使用 `PUT /confirm`；改成 POST 属破坏性变更，会让前端/后端同时返工。
2. confirm 与 freeze 是订单资源的目标状态迁移（`DRAFT→CONFIRMED`、`UNFROZEN↔FROZEN`），PUT 的幂等语义更贴合；POST 动作风格需要额外 `/unfreeze` 端点。
3. 解冻是 `freezeStatus=UNFROZEN` 的目标状态，一个端点即可覆盖冻结/解冻，减少一条路径与一套错误码。
4. 若后续必须兼容任务卡的 POST 风格，需走契约变更单，并以别名或 `/api/v2` 提供；**M1 不做别名**。

### 18.3 更新订单契约

请求体 `OrderUpdateRequest`（纯增量，响应结构不变）：

| 字段 | 必填 | 类型 | 规则 |
|---|---|---|---|
| `version` | 是 | integer ≥1 | 必须等于当前 `orders.version`，否则 40901 |
| `materialCode` | 否 | string | `orders.product_id` → `material.code`；物料必须存在且未删除，否则 42200 |
| `qty` | 否 | number > 0 | `qty <= 0` → 42200 |
| `dueDate` | 否 | date | 非法日期 → 40000/42200 |
| `priority` | 否 | enum `OrderPriority` | 非法值 → 40000 |
| `customerId` | 否 | UUID / null | 客户不存在 → 42200（M1 不做黑名单，见 C8） |
| `routingId` | 否 | UUID / null | routing 必须存在且属于当前租户，否则 42200 |
| `remark` | 否 | string / null | — |
| `extAttrs` | 否 | object | 必须是 JSON object，否则 40000 |

规则：

- `orderNo` 不可修改；请求体不提供该字段。
- `status != DRAFT` → 42200；`deleted=true` → 40400。
- 成功时 `version` 原子 +1，返回更新后的订单。
- 乐观锁失败 → `40901`；重复/业务键冲突 → `40900`。
- 本接口不要求 `Idempotency-Key`；`version` 即并发保护，成功后重放会因版本不匹配得到 40901，客户端应刷新后重试。

### 18.4 确认 / 冻结契约

- `PUT /confirm`：无请求体；`DRAFT → CONFIRMED`；重复确认返回 200（目标状态幂等）；非 DRAFT → 42200；产品/BOM/routing 校验按 C8。
- `PUT /freeze`：请求 `freezeStatus` + `version`；`UNFROZEN ↔ FROZEN/LOCKED_*` 迁移；重复请求同一目标状态返回 200；`version` 不一致 → 40901；非法状态值 → 40000。
- **幂等键**：两接口均为**目标状态幂等**，`Idempotency-Key` 可选（传入时按 C7 校验 8–128 位并缓存结果），不强制；重复请求不重复写审计/不重复发事件。
- 响应统一 `ResultOfOrder`，`version` 返回最新值。

### 18.5 状态流转与取消/关闭

| 动作 | M1 契约 |
|---|---|
| `DRAFT → CONFIRMED` | `PUT /confirm`（C8 校验） |
| `CONFIRMED → SCHEDULED → RELEASED → IN_PROGRESS → COMPLETED` | 由求解/执行流推进，不在订单动作端点内 |
| `CANCELLED` | **M1 不涉及**：契约无取消/关闭端点，枚举保留 |
| 冻结轴 `UNFROZEN/FROZEN/LOCKED_*` | 仅 `PUT /freeze` 承载；`LOCKED_*` 的写入口不在 M1 |

结论：M1 订单动作只有「更新（DRAFT）」「确认」「冻结/解冻」三类；取消/关闭接口 M1 不涉及，若后续要加，走契约变更单并复用 `ResultOfOrder`。

---

## 19. C14 资源与日历契约补全（已确认）

> 依据：PRD 4.1（FR-MD-003 资源、FR-MD-004 日历）、`V1__init.sql` 的 `resource`（第 330–377 行）与 `calendar`（第 379–425 行）、C6、C10、C12。
> 消费方：FE-02 资源页、FE-03 日历页；实现方：BD-02（资源）、BD-05（日历）。S0 只改契约。

### 19.1 端点补全

| 端点 | 说明 | 关键错误 |
|---|---|---|
| `GET /api/v1/resources/{code}` | 资源详情，路径用 code，响应带 `id` | 40400 |
| `PUT /api/v1/resources/{code}` | 更新资源，body 必填 `version` | 40901 版本冲突、42200 校验失败 |
| `DELETE /api/v1/resources/{code}` | 停用（`deleted=true` 软删除），不物理删除 | 40400；被日历/任务引用 → 42200 |
| `GET /api/v1/calendars/{id}` | 日历详情，路径用 UUID | 40400 |
| `PUT /api/v1/calendars/{id}` | 更新日历，body 必填 `version` | 40300/403 历史日历只读、40901 版本冲突 |

- 资源 `PUT`：`code` 不可改；成功 `version` 原子 +1；返回 `ResultOfResource`。
- 资源 `DELETE`：幂等，重复调用 200；被 `calendar.resource_id` 或 `task.resource_id` 引用时拒绝（42200）。
- 日历 `PUT`：`calendarVersion` 不可改，新版本走创建接口；`effectiveTo < 今天` 的日历只读，`effectiveTo` 为空视为长期有效、可更新。

### 19.2 四条数据结论

1. **资源响应暴露 `id`**：`Resource` schema 新增只读 `id`（UUID）。资源仍用 `code` 寻址，但前端绑定日历时必须用 `id` 填 `calendar.resourceId`，禁止让人手填 UUID。
2. **`calendarVersion` 以 schema 为准为 INTEGER**：`calendar.calendar_version` 是 `INTEGER`（V1 第 392 行），YAML 原写 `string`（示例 `V1.0`）属错误，已改为 `integer`。递增规则：同 `tenant + name` 新建版本时取当前最大 `calendar_version + 1`；历史版本只读、不原地修改；唯一索引 `uq_calendar_tenant_name_version` 保障。
3. **`resource` 不引入 `status`**：schema 只有 `deleted BOOLEAN`（V1 第 359 行），M1 用 `deleted=true` 表达停用/软删除；资源列表与响应**不新增** `status` 字段、列表**不新增** `status` 参数。若前端已按可选 `status` 做兼容展示，那只是**客户端兼容逻辑，不是契约字段**，不得写回请求或依赖后端返回。
4. **`GET /calendars/{id}/available` M1 不涉及**：端点保留为 `placeholder`，M1 不实现。求解所需可用时段由服务层按 C12 在内部展开（`availabilityWindows`），前端日历详情直接用 `shifts/holidays` 展示，不调用该端点。

### 19.3 筛选参数

| 端点 | 结论 |
|---|---|
| `GET /resources` | `code`/`name` 模糊（OR）、`type`/`groupId` 精确（AND）**已具备**（C10）；**不加 `status`** |
| `GET /calendars` | `name` 模糊、`type` 精确**已具备**（C10 同时已有 `resourceId`/`resourceGroupId`） |

语义沿用 C10：`code/name` OR，与其他条件 AND。

### 19.4 HTTP 与错误码映射

| 业务码 | HTTP | 场景 |
|---|---|---|
| 40300 | 403 | 历史日历（`effectiveTo < 今天`）更新 |
| 40901 | 409 | 资源/日历 `version` 与当前值不一致 |
| 42200 | 422 | 资源被日历/任务引用仍删除、非法产能组合、日历区间非法 |
| 40400 | 404 | code/UUID 不存在或已删除 |

### 19.5 任务卡/前端预留与最终契约差异

| 预留/旧描述 | 最终契约 | 处理 |
|---|---|---|
| `POST /resources/{code}/disable` 或物理删除 | `DELETE /resources/{code}` 软删除 | 采用 DELETE；不提供 `/disable`、不物理删除 |
| `PATCH /resources/{code}` 局部更新 | `PUT /resources/{code}` + `version` | 采用 PUT；前端按 PUT 调整 |
| 资源响应无 `id`，日历 `resourceId` 需手填 | 响应带 `id` | 前端改用 `id` 绑定 |
| `calendarVersion` 为字符串（`V1.0`） | INTEGER（`1`） | 前端类型改 number；旧值需迁移展示 |
| 资源可选 `status` 字段/筛选 | 无 status，只有 `deleted` | 前端兼容展示可保留，但不得作为契约参数 |
| `GET /calendars/{id}/available` 视为 M1 必做 | M1 不涉及，保留占位 | 求解流内部展开，前端不调用 |
| 日历“新建版本”路径 `POST /calendars/{id}/versions` | M1 未定义独立版本端点 | 用 `POST /calendars` 携带递增 `calendarVersion` |

### 19.6 日历详情对象与时间语义（C14 增量，W7 反馈）

`GET /api/v1/calendars/{id}` M1 已实现，但此前 `materials.yaml` 只有路径没有对象结构；现已补齐 `Calendar` schema（`docs/api/openapi/materials.yaml`），字段如下：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | `calendar.id`，路径寻址用 |
| `name` | string | 日历名；`tenant + name + calendarVersion` 唯一 |
| `calendarVersion` | integer | 业务版本号 `calendar.calendar_version`，历史版本只读 |
| `type` | enum | `FACTORY / RESOURCE / SHIFT`（V1 `chk_calendar_type`） |
| `resourceId` / `resourceGroupId` | UUID? | 资源专属 / 资源组级；`FACTORY` 时 `resourceId=null` |
| `effectiveFrom` / `effectiveTo` | date / date? | 生效期；`effectiveTo=null` 表示长期有效 |
| `timezone` | string? | **C14 新增**：IANA 时区名，见下方"时间语义" |
| `shifts[]` | array | `{startTime, endTime, daysOfWeek:[1..7]}`，**工厂墙钟时间**（`HH:mm`） |
| `holidays[]` | array | `{date, name, kind}`，`kind ∈ LEGAL / COMPANY / MAKEUP` |
| `version` | integer | 乐观锁版本；`PUT` 必填，不一致 `40901` |

**时间语义（冻结）**：

1. 班次 `shifts[].startTime/endTime` 与例外日 `holidays[].date` 一律按**日历 `timezone`** 解释；`timezone` 缺省取服务端配置 `aps.calendar.timezone`（默认 `Asia/Shanghai`）。
2. **禁止**按浏览器本地时区解释班次；前端只做时区换算展示，不参与"哪一天/哪一时段"的判定。所有跨日班次（`endTime < startTime`）按该时区归入 `startTime` 当天。
3. M2 **不新增 `calendar.timezone` 列**：`timezone` 为只读投影，来自上述配置；若后续确需多时区日历，再单独发迁移号（不得复用 V2–V6）。

**调休（`kind=MAKEUP`）班次来源规则（冻结）**：

1. `MAKEUP` 例外日若能在 `shifts` 中匹配到当日星期（`daysOfWeek` 覆盖），直接使用匹配的班次。
2. 匹配不到（如原为周末、日历只配了周一至周五）时，按**"调休推定"**处理：沿用该日历**全部班次时段的并集**作为当日可用时段，并在响应/前端标注「调休推定 N 天」（N 为该日历被推定的 `MAKEUP` 日数）。
3. 后端在 M2 **不自动改写 `calendar.shifts`**；推定只发生在读取侧（求解展开与前端展示口径一致）。
4. `LEGAL` / `COMPANY` 例外日为整天不可用（不产生可用时段），不适用推定。

---

## 20. C15 工艺路线图与工序模版（已确认）

> 依据：PRD 4.2（FR-PR-001~004）、`V1__init.sql` 的 `routing`（第 467–496 行）、`routing_node`（第 498–529 行）、`routing_edge`（第 531–553 行）、`operation_template`（第 430–462 行）、`docs/db/schema.md` 5.5。
> 消费方：FE-04 工艺路线编辑器；实现方：BD-07（工艺路线）、SE-04（问题装配）。S0 只改契约。

### 20.1 端点与筛选参数

| 端点 | 说明 | 关键错误 |
|---|---|---|
| `GET /api/v1/routings/{id}/graph` | 读取 DAG（routing UUID） | 40400 |
| `PUT /api/v1/routings/{id}/graph` | 整体替换 nodes/edges，body 必填 `version` | 40901 版本冲突、42200 有环/seqNo 重复/资源不存在 |
| `GET /api/v1/operation-templates` | 工序模版列表，供编辑器选择 | — |

- `GET /routings` 新增筛选：`productCode`（精确，product_id → material.code）、`version`（业务版本号 `routing_version`，字符串）、`status`（`RoutingStatus` DRAFT/ACTIVE/INACTIVE）；条件 AND。
- `GET /operation-templates`：分页；`code`/`name` 模糊 OR；`resourceType` 精确 AND（`primary_resource_id → resource.type`）。
- 注意两个 `version` 语义不同：列表筛选的 `version` 是业务版本 `routing_version`（字符串）；PUT body 的 `version` 是乐观锁整数（`routing.version`）。

### 20.2 node / edge 与真实列映射

| 契约字段 | 真实列 / 持久化位置 | 类型 |
|---|---|---|
| `seqNo` | `routing_node.seq_no` | integer，路线内唯一 |
| `operationTemplateId` | `routing_node.operation_template_id` | UUID，可空 |
| `operationCode` / `operationName` | `routing_node.operation_code/name` | 可空 |
| `standardSeconds` / `setupSeconds` | `routing_node.standard_seconds/setup_seconds` | integer，可空 |
| `primaryResourceId` | **`routing_node.overrides.primaryResourceId`** | UUID |
| `secondaryResourceIds` | **`routing_node.overrides.secondaryResourceIds`** | UUID[] |
| `optional` | `routing_node.is_optional` | boolean |
| `fromSeqNo` / `toSeqNo` | 服务端映射到 `routing_edge.from_node_id/to_node_id` | integer |
| `dependType` | `routing_edge.depend_type` | FS/SS/FF（CHECK 不支持 SF） |
| `lagMinutes` | `routing_edge.lag_minutes` | integer ≥0 |

**重要更正**：V1 的 `routing_node` **没有** `primary_resource_id` 列（该列在 `operation_template`）；节点级资源覆盖按 `schema.md` 5.5 存在 `overrides` JSONB。`routing_edge` 存的是 node UUID，不是 seqNo，`fromSeqNo/toSeqNo` 由 BD-07 在保存时映射。

### 20.3 资源引用最终结论：方案 B（UUID）

**结论一句话**：路由节点对外一律使用 `primaryResourceId`（UUID），前端选择器改用资源响应里的 `id`，不再传 `resource_code`。

理由：

1. 持久化位置 `overrides.primaryResourceId` 本身就是 UUID，直接落库无转换。
2. 避免每个节点一次 code→id 查询与 code 唯一性依赖，保存大图时性能更稳。
3. C14 已要求资源响应带 `id`，日历绑定已采用 UUID；路由沿用同一规则，前端只需一个选择器取值口径。
4. SE-04 问题装配直接消费 UUID，无需再做映射；`SchedulingResource.id` 也是 UUID，链路一致。
5. code 虽唯一但属业务键，改名/换码会波及历史路线；UUID 不受影响。

影响面：

| 方 | 影响 |
|---|---|
| FE-04 | 资源下拉 `value=resource.id`，展示 `code/name`；请求节点改用 `primaryResourceId`；不再发送 `resource_code` |
| BD-07 | 校验 `primaryResourceId` 对应资源存在且未删除；写入 `routing_node.overrides.primaryResourceId`；`secondaryResourceIds` 写 `overrides.secondaryResourceIds`；边按 `seqNo` 映射 node UUID；生成 `node_key` |
| SE-04 | 从 `overrides.primaryResourceId` 读取主资源，未覆盖时回退 `operation_template.primary_resource_id`；无 code→id 映射 |

兼容说明：`RoutingNode.resourceCode` 与 `RoutingEdge.from/to/minLagMinutes` 保留为 **readOnly + deprecated** 的响应兼容字段，请求不再使用；若请求只给 `resourceCode` 而不给 `primaryResourceId`，BD-07 返回 42200。

### 20.3.1 `routing_node.overrides` 白名单与读写口径（2026-11-04 裁定：方案 A）

**裁定**：采用 **A**。不新增列、不提 V7；节点主资源存储于 `routing_node.overrides`，API 层映射为顶层 `primaryResourceId`。V2–V6 已全部应用，方案 B 的 V7 仅在确有列需求时由 S0 发号，且已应用迁移一律禁改。

| 键 | 类型 | 必填 | 三态 | 说明 |
|---|---|---|---|---|
| `primaryResourceId` | `string`（UUID）或 `null` | 否 | 缺键=未覆盖→模板回退；`null`=显式主资源浮动；`"<uuid>"`=固定 | 节点级主资源覆盖；三态**不等价**，见 20.3.1.1 |
| `secondaryResourceIds` | `array<string>`（UUID） | 否 | 缺键=未覆盖→模板回退；`[]`=显式无辅助资源；`["<uuid>"...]`=显式列表 | 节点级辅助资源覆盖，按数组顺序；见 20.3.1.3 |

- `overrides` 必须是 JSON object（V1 `chk_routing_node_overrides_object`），**只允许上表两个键**，禁止出现其他键。
- **请求侧不暴露 `overrides`**：`PUT /routings/{id}/graph` 的节点字段是顶层 `primaryResourceId` / `secondaryResourceIds`；BD-07 保存时只写这两个白名单键（其余键不落库，`RoutingOverrides.build` 是唯一写入口）。
- **响应侧取值来源**：`GET /routings/{id}`、`GET /routings/{id}/graph` 的 `primaryResourceId` / `secondaryResourceIds` **只从 `overrides` 提升**；未覆盖时返回 `null`，**不以 `operation_template` 回填**。
- 模板回退只发生在 SE-04 排产装配（`ScheduleProblemAssembler`）：`overrides.primaryResourceId` → `operation_template.primary_resource_id` → `primary_resource_group_id` 下任一资源 → 场景唯一资源；仍无主资源则 `42200`。
- UUID 一律字符串形式，读取端用 `UUID.fromString` 严格解析；非法字符串不属于合法存储，BD-07 必须在保存前校验（不合法 `42200`）。
- **禁止归一化**：任何读写/校验层都不得把“缺键”与“`null`”混为一谈，也不得把“缺键”与“`[]`”混为一谈。

#### 20.3.1.1 `primaryResourceId` 三态（缺键 ≠ `null`）

| 存储形态 | 名称 | 语义 | 排产装配（SE-04）行为 |
|---|---|---|---|
| 键不存在 | 未覆盖 `INHERIT` | 节点不表达主资源意图 | 回退 `operation_template.primary_resource_id` → `primary_resource_group_id` 下任一资源 → 场景唯一资源 → 仍无则 `42200` |
| `"primaryResourceId": null` | 显式浮动 `FLOAT` | 节点**显式声明**“主资源不固定，交给求解器选” | **跳过**模板与资源组回退，直接 `primaryResourceId = null`；求解器可分配到任意资源 |
| `"primaryResourceId": "<uuid>"` | 固定 `FIXED` | 节点锁定主资源 | 校验资源存在且未删除；求解器只能排在该资源 |

- **缺键与 `null` 不等价**。`ScheduleProblemAssembler.primaryResourceId` 用 `overrides.containsKey("primaryResourceId")` 判定三态；`RoutingOverrides.primaryResourceId()` 用 `get` **无法区分**两者，只能用于展示/校验，**不得用于装配判定**。
- 显式 `null` 是 M2 的正式语义，不是脏数据：V1 的 `chk_routing_node_overrides_object` 只约束 `overrides` 为 object，允许键值为 `null`。
- `FLOAT` 工序是唯一能被 C21 `RESOURCE` 锁收缩资源集合的工序（见 20.3.1.2）。

#### 20.3.1.2 与 C21 `RESOURCE` 锁的叠加：固定工序上锁是空操作（不是 bug）

- 硬约束 `primaryResourceOnly`（`ConstraintPredicates.primaryResourceViolated`）：`primaryResourceId != null && plannedResourceId != primaryResourceId` → 罚 hard。**主资源固定的工序，可行资源只剩一个**。
- 因此 `lockType=RESOURCE` 的 `lockedResourceIds` 对**固定工序是空操作**：白名单写几个资源都一样，能排的只有那一个。这不是锁未生效，而是 `primaryResourceOnly` 与锁叠加后可行域收敛；**验收/排障不得据此判定 `RESOURCE` 锁失效**。
- 要让 `RESOURCE` 锁真正改变求解结果，必须把该工序写成 `overrides.primaryResourceId = null`（`FLOAT`），再由锁把可分配集合收缩到 `lockedResourceIds`；`lockedResourceIds` 单元素时等价固定，多元素时是白名单内择优。
- 排障口径：`lockType=RESOURCE` 但结果与无锁完全一致 → 先查该任务主资源是否为固定值；固定则属预期，改 `FLOAT` 后复测。

#### 20.3.1.3 `secondaryResourceIds` 三态（缺键 ≠ `[]`）

| 存储形态 | 语义 | 排产装配行为 |
|---|---|---|
| 键不存在 | 未覆盖 | 回退 `operation_template.secondary_resource_ids`（模板无值则空数组） |
| `[]` | 显式“无辅助资源” | **不回退模板**，按空数组处理 |
| `["<uuid>"...]` | 显式辅助资源 | 按数组顺序使用，不回退模板 |

- `RoutingOverrides.build` 对 `null` 不写键、对 `[]` 写空数组；`ScheduleProblemAssembler.secondaryResourceIds` 用 `containsKey` 判定，**三态已对齐**。
- 非数组值属非法存储，装配时 `42200`，不静默降级为空数组。
- 本键**不需要额外的模式枚举**：缺键/`null`、`[]`、数组 在 JSON 与 `List` 上天然可区分（见 20.3.1.5）。

#### 20.3.1.4 浮动工序的时长口径（2026-11-04 冻结）

- 时长公式以 C2 为准（`DurationCalculator`）：`UNIT_PER_HOUR` 用 `capacity_value × efficiency`，`BATCH_SECONDS` 用 `capacity_value`/`batch_capacity`/`efficiency`，`SECONDS_PER_UNIT` 用 `standard_seconds`/`efficiency`。**三种模式都显式读取资源字段**——`SECONDS_PER_UNIT` 也非绝对“与资源无关”：`efficiency` 不同则时长不同。
- 求解模型在**建模型时**为每个任务算一次 `runSeconds` 并写死（`TimefoldModelBuilder`），资源归属由 `ResourcePlan` 的 list variable 决定；**换资源不会重算时长**。因此浮动工序必须有确定口径的“标称资源”：
  1. `primaryResourceId` 固定 → 该资源；
  2. 浮动 + `lockType=RESOURCE` 且 `lockedResourceIds` 非空 → `lockedResourceIds` 中 `resource.code` 字典序最小者；
  3. 浮动 + 无资源锁 → 场景内全部启用资源中 `resource.code` 字典序最小者（`ScheduleProblemAssembler.buildResources` 已按 `code` 升序，`resolveResource` 取首个，顺序确定）。
- **前置校验（必须实现）**：浮动工序的候选资源集合内，各资源对该工序算出的 `runSeconds` 必须一致；不一致时装配阶段返回 `42200`（`message` 指明工序与冲突资源），**不得静默取第一个资源代算**。
  - 候选集合：`RESOURCE` 锁生效时为 `lockedResourceIds`，否则为场景内全部启用资源。
  - 等价条件（满足其一即可）：资源间 `capacity_mode`、`capacity_value`、`efficiency`、`batch_capacity` 相同；`SECONDS_PER_UNIT` 场景下只要求 `efficiency` 相同。
- **现状缺口（W1 实测，W4 必修）**：`TimefoldModelBuilder.resolveResource` 对无主资源工序直接取 `resourceById` 迭代顺序第一个作为标称资源（既不按 `lockedResourceIds` 收敛，也不做时长一致性校验）；在 `UNIT_PER_HOUR`/`BATCH_SECONDS` 或 `efficiency` 不一致时，会用错资源算 `runSeconds`。契约口径以本节为准。
- 若后续确需“混合产能资源下浮动”，应改为“按实际分配资源重算时长”（`runSeconds` 需成为随资源变化的派生量），属模型改造，另行立条款，不在本条范围。

#### 20.3.1.5 API 表达与现状缺口（前端 / BD-07 按此实现）

- **现状缺口**：`RoutingNodeCommand.primaryResourceId` 是 `UUID`，Jackson 反序列化后无法区分“缺键”与“`null`”；`RoutingOverrides.build` 又只写非空值。因此当前 `PUT /routings/{id}/graph` **无法表达**、`GET` **无法回显**“显式浮动”（W4 的 `FLOAT` 实验数据来自直写 JSONB）。
- **冻结的 API 表达**：节点请求新增 `primaryResourceMode`，取值 `INHERIT | FLOAT | FIXED`：
  - 字段缺省 = `INHERIT`（等价 overrides 无键）；
  - `FLOAT` → `primaryResourceId` 必须缺省或为 `null`（给非空 UUID → `42200`），保存时写 `"primaryResourceId": null`；
  - `FIXED` → 必须给 `primaryResourceId`（UUID），保存时写字符串；缺 `primaryResourceId` → `42200`；
  - 兼容：不带 `primaryResourceMode` 但带 `primaryResourceId` 的旧请求按 `FIXED`；两者都不带按 `INHERIT`。
- `GET /routings/{id}` 与 `GET /routings/{id}/graph` 的节点响应必须同时返回 `primaryResourceMode`（由 `containsKey` + 值判定），否则 `primaryResourceId=null` 无法区分 `INHERIT` 与 `FLOAT`。
- `secondaryResourceIds` 不需要新字段：`null`/缺键=`INHERIT`、`[]`=显式空、数组=显式列表。

### 20.4 FE-04 其他扩展端点

本轮只发现 `GET /operation-templates` 一个新端点；`routings` 的 activate/versions 等未在契约中定义，FE-04 也未提出。M1 如需要，走契约变更单，不私自新增路径。

### 20.5 与任务卡/前端预留差异

| 旧描述/实现 | 最终契约 |
|---|---|
| 节点传 `resource_code` | 改传 `primaryResourceId`（UUID） |
| 节点用 `sequence` | 用 `seqNo`（旧字段 readOnly 兼容） |
| 边用 `from/to/minLagMinutes` | 用 `fromSeqNo/toSeqNo/dependType/lagMinutes`（旧字段 readOnly 兼容） |
| 无工序模版列表端点 | 新增 `GET /operation-templates` |
| 列表无 productCode/version/status | 已补三个筛选参数 |
| 无 routing 图写入端点 | 新增 `PUT /routings/{id}/graph`，带 `version` 乐观锁 |

---

## 21. C16 工序模版寻址与 process 分组（已确认）

> 依据：`V1__init.sql` 的 `operation_template`（第 430–462 行，`template_version` + 唯一索引 `tenant_id, code, template_version`）、`docs/api/conventions.md` 第 9 节、FE-04 联调反馈。
> 消费方：FE-04；实现方：BD-07、`OpenApiConfiguration` 维护者。S0 只改契约。

### 21.1 最终结论

**工序模版详情一律用 UUID**：`GET/PUT/DELETE /api/v1/operation-templates/{id}`，`{id}` 为 `operation_template.id`。**不允许**任何 `{code}` 形式的详情路径。

理由：同一 `code` 在 `operation_template` 中可对应多个 `template_version`（唯一索引是 `tenant_id + code + template_version`），`{code}` 寻址天生歧义，无法确定返回哪个版本。

### 21.2 端点定稿

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/operation-templates` | 列表；`code`/`name` 模糊 OR，`resourceType` 精确 AND（不变） |
| `POST` | `/api/v1/operation-templates` | 创建；`code` 在**请求体**中，路径不含 code；`code + templateVersion` 重复 → 40900 |
| `GET` | `/api/v1/operation-templates/{id}` | 详情（UUID） |
| `PUT` | `/api/v1/operation-templates/{id}` | 更新（UUID）；`version` 乐观锁 → 40901；`code/templateVersion` 不可改 |
| `DELETE` | `/api/v1/operation-templates/{id}` | 软删除（UUID）；被 `routing_node.operation_template_id` 引用 → 42200 |

- `POST` 的 `code` 是**请求体字段**，不是路径参数；列表筛选的 `code` 仍是查询参数。
- 新版本通过 `POST` 创建并递增 `templateVersion`，不通过 `PUT` 原地修改。
- 已有 `GET /routings` 等工艺路线端点路径不变。

### 21.3 历史事实与收敛

仓库里工序模版曾有 `{code}` 与 `{id}` 两套并行实现/描述；现**统一收敛到 `{id}`**。本契约与 `materials.yaml` 为唯一基线，任何旧文档、旧任务卡中的 `{code}` 详情路径均作废，不得据此回写。

### 21.4 `process` 分组规则原文

`docs/api/conventions.md` 已登记 `process` 分组，`OpenApiConfiguration` 实现者照抄：

```java
GroupedOpenApi.builder()
        .group("process")
        .pathsToMatch("/api/v1/operation-templates/**", "/api/v1/routings/**")
        .build();
```

同时**必须**从 `materials` 分组移除 `/api/v1/routings/**`：

```java
GroupedOpenApi.builder()
        .group("materials")
        .pathsToMatch("/api/v1/materials/**", "/api/v1/boms/**", "/api/v1/resources/**",
                "/api/v1/calendars/**", "/api/v1/mrp/**")
        .build();
```

- 分组名固定 `process`；`/v3/api-docs/process` 必须包含 `/operation-templates/**` 与 `/routings/**`。
- 静态契约目前仍在 `materials.yaml`（工序模版与工艺路线同文件），`process` 分组只影响运行时文档；后续如拆分 `process.yaml`，以本规则为准同步。

---

## 22. C17 BOM/日历删除与实现状态登记（已确认）

> 依据：后端实际 Controller（BD-02/BD-03/BD-05 已交付）、`V1__init.sql` 的 `bom/bom_item/calendar`、`docs/api/openapi/README.md` 的状态取值规则。
> 消费方：FE-02/FE-03；实现方：已交付后端。S0 只登记契约。

### 22.1 新增删除端点

| 端点 | 语义 | 响应/错误 |
|---|---|---|
| `DELETE /api/v1/boms/{bomId}` | 逻辑删除 BOM 头**及其自身明细**（items），不物理删除 | 200（`Result` 无 data）；40400/404；被 `orders.product_id` 或其他 BOM 子件行引用 → 42200/422 |
| `DELETE /api/v1/calendars/{id}` | 日历软删除（`calendar.deleted=true`） | 200（`Result` 无 data）；40400/404 |

- BOM **没有**独立“停用”端点，删除即逻辑删除；契约不补 `/disable` 或 `/deactivate`。
- 日历删除**不区分历史**：历史只读限制只作用于 `PUT`（40300/403）；`DELETE` 对历史日历行为与普通日历一致，不额外返回 40300。
- 两者路径参数均为 UUID，与 C6/C14 一致。

### 22.2 `x-implementation-status` 同步规则

按 `docs/api/openapi/README.md`，已落地端点使用 `implemented`，未落地保持 `placeholder`。本轮已改为 `implemented` 的操作：

| 域 | operationId |
|---|---|
| 物料 | `listMaterials`、`createMaterial`、`getMaterial`、`updateMaterial`、`deleteMaterial` |
| BOM | `listBoms`、`createBom`、`getBom`、`bomTree`、`activateBom`、`deleteBom` |
| 资源 | `listResources`、`createResource`、`getResource`、`updateResource`、`deleteResource` |
| 日历 | `listCalendars`、`createCalendar`、`getCalendar`、`updateCalendar`、`deleteCalendar` |
| 工序模版 | `listOperationTemplates`、`createOperationTemplate`、`getOperationTemplate`、`updateOperationTemplate`、`deleteOperationTemplate` |

同时在描述首行把「状态：占位」改为「状态：已实现」。

### 22.3 仍保持 placeholder 的清单

| 端点/操作 | 原因 |
|---|---|
| `POST /materials/import`（`importMaterials`） | 仍返回 501 |
| `POST /resources/{code}/maintenance`（`maintainResource`） | 仍返回 501 |
| `GET /calendars/{id}/available`（`availableWorkingTime`） | M1 不涉及（C14） |
| `GET/POST /routings`、`GET/PUT /routings/{id}/graph` | BD-07 开发中，按指示保持 placeholder |
| `POST /mrp/analysis`（`mrpAnalysis`） | 未落地 |
| `orders/solve/gantt/system` 全部端点 | 本轮未改动，按各自原状态 |

---

## 23. 待产品确认

1. **C2 全部公式**为默认假设：`efficiency` 乘产能 / 除工时的业务口径、整秒向上取整、`batch_capacity` 空按 1、换型时间独立计。
2. 客户黑名单（FR-MD-005）M1 跳过已确认；后续里程碑的过滤口径（按客户/按订单/按产品线）仍待产品定义。
3. 求解引擎对外是否统一为小写 code（当前保留兼容），以及 `UNSUPPORTED` 是否改为 `42200`。
4. `solve_task.strategy_id` 为空时使用哪套默认权重（当前按 PRD 默认策略，具体权重待策略流给出）。

---

## 24. M2 契约冻结（C19–C29）

> 状态：**冻结**（2026-11-04，M2-D0）。写入人：W1 `dev-CONTRACT`。
> 决策来源：用户 2026-11-04 对 M2 七项默认决定的最终拍板，落地口径见 `docs/plan/m2-plan.md` §8。
> 编号规则：C19–C29 为新增编号，**不改 C1–C17 的编号与结论**；C12 顶部已加「DSL 语法以 C19 为准」。
> 通用约定（返回体、HTTP 语义、时间格式、分页）不变，见 `docs/api/conventions.md`。

### 24.1 C19 策略规则与受控 DSL（唯一写者 W2 `dev-SE1`）

> **维度裁定（2026-11-04）**：DSL **以 `code` 为维度，不经 `strategy_id`**；端点为 `/api/v1/constraint-dsls/**`。
> V4 `constraint_dsl` 不含 `strategy_id`，**不补该列**——策略实例与规则集编码解耦，避免出现两套寻址方式。

**策略对象**：`Strategy` 在 M2 增加 `rules` 数组，替代 M1 的 `initialRules`/`optimizationRules` 两个自由 `object`（两字段标记 `deprecated: true`，仅保留读取兼容）。

| 字段 | 类型 | 约束 |
|---|---|---|
| `id` | UUID | 服务端生成 |
| `name` | string | 租户内 `name + strategyVersion` 唯一 |
| `direction` | enum | `FORWARD/BACKWARD/BOTTLENECK` |
| `strategyVersion` | int | 从 1 递增 |
| `effectiveFrom` / `effectiveTo` | date | `effectiveTo` 可空；`to >= from` |
| `applyCondition` | object | `productLines: string[]`、`customers: string[]`，空数组表示全部 |
| `rules` | array | 见下；**M2 规范字段** |
| `version` | int | 乐观锁，更新必须携带 |

**规则条目 `StrategyRule`**：

```json
{ "code": "MIN_TARDINESS", "enabled": true, "weight": 20, "params": {} }
```

- `code` 白名单（8 条核心软约束，PRD FR-SS-002 / 1551）：

| code | 名称 | 默认权重 |
|---|---|---|
| `MIN_TARDINESS` | 最小超期 | 20 |
| `EDD` | 交期优先 | 15 |
| `PRIORITY_RESPECT` | 优先级尊重 | 15 |
| `SPT` | 短任务优先 | 10 |
| `SAME_PRODUCT_CONTINUOUS` | 同品连续生产 | 10 |
| `MIN_SETUP_TIME` | 最小换型时长 | 10 |
| `BALANCE_LOAD` | 均衡负载率 | 10 |
| `MIN_MAKESPAN` | 最小总完工时间 | 10 |

- `weight` 为整数 0–100，`0` 等价 `enabled=false`；**已启用规则权重之和必须等于 100**，否则 `42200`。
- `enabled` 缺省 `true`；`params` 仅允许各规则登记的键，未知键 `42200`。
- 硬约束（`resourceNoOverlap`、`calendarAvailability`、`operationDependency`、`frozenResource`、`frozenStartTime`、`primaryResourceOnly`）**不进入策略数组**，M2 固定启用。

**端点**：

| 方法/路径 | 说明 | 错误 |
|---|---|---|
| `GET /strategies` | 分页；支持 `name`、`direction`、`effectiveOn` | — |
| `POST /strategies` | 创建 | 40901 名称+版本重复、42200 权重和≠100 |
| `GET /strategies/{id}` | 详情（含 `rules`） | 40400 |
| `PUT /strategies/{id}` | 全量更新（`rules` 必填、`version` 必填） | 40901 版本冲突、42200 |
| `POST /strategies/{id}/clone` | 克隆，生成新 `strategyVersion` | 40400 |
| `GET /strategies/{id}/export` | 导出 `{strategy, rules, checksum}`（JSON） | 40400 |
| `POST /strategies/import` | `{payload, dryRun}` → `{valid, errors[], strategyId}` | 42200 |

**DSL 端点（按 `code` 维度，唯一写者 W2；均返回 `Result<...>`）**：

| 方法/路径 | 请求 | 响应 / 错误 |
|---|---|---|
| `POST /constraint-dsls/validate` | `{yamlText}`（必填，≤256KB） | 200 + `DslValidationReport{valid, issues[{code, field, message}]}`；**不落库**，非法 DSL 仍返回 200（报告内 `valid=false`） |
| `POST /constraint-dsls` | `{code, yamlText}` | 200 + `ConstraintDslView`（新 DRAFT 版本，`versionNo` 自增）；校验失败 `42200`（message 附逐条 422xx 明细） |
| `GET /constraint-dsls?code=xxx` | — | 200 + 该 `code` 的全部版本，`versionNo` 倒序 |
| `GET /constraint-dsls/{id}` | — | 200 + `ConstraintDslView`；`40400` |
| `POST /constraint-dsls/{id}/activate` | 无 body | 200 + `ConstraintDslView`（状态 ACTIVE）；同 `code` 仅一条 ACTIVE；失败自动恢复上一 ACTIVE 并 `40901` |
| `POST /constraint-dsls/{id}/rollback` | 无 body | 200 + `ConstraintDslView`；当前 ACTIVE 置 `ROLLBACK`、目标版本置 `ACTIVE` 并记 `rollbackOf`；失败 `40901` |

**`ConstraintDslView` 字段**：`id`、`code`、`versionNo`、`status`（`DRAFT/ACTIVE/ROLLBACK`）、`checksum`（规范化 YAML 的 SHA-256）、`rollbackOf`、`effectiveFrom`、`yamlText`、`compiledSpec`（约束快照数组）、`createdAt`。

**受控 DSL（YAML v1.0）**：

```yaml
version: "1.0"
constraints:                      # 规范：数组
  - id: min_tardiness
    ruleCode: MIN_TARDINESS       # 白名单
    type: SOFT
    weight: 20
    enabled: true
    params: {}
constraint:                       # 兼容别名：单对象，等价 constraints 的单元素数组
  id: edd
  ruleCode: EDD
  weight: 15
```

冻结规则：

1. **`constraints[]` 为唯一序列化输出**；输入接受单数 `constraint:`（对象），解析时转为单元素数组。开关 `aps.dsl.legacy-singular` 默认 `true`，**保留到 M2 结束**，M3 清理。
2. 同时出现 `constraints` 与 `constraint` → `42200`（歧义）。
3. `type` 在 M2 只允许 `SOFT`；`ruleCode` 只允许 8 条软约束；`stream`、`condition` 等任意代码字段**一律拒绝**（`42200`），不做类加载、不做运行时编译。
4. YAML 使用 safe load，单文档 ≤256KB；未知字段、未知 `ruleCode`、权重越界均 `42200`，返回 `{field, code, message}` 列表。
5. `constraint_dsl` 落库字段（V4）：`id/tenant/code/version_no/yaml_text/compiled_spec JSONB/status/checksum/rollback_of/effective_from/created_by/审计字段`；同 `code` 仅一条 `ACTIVE`。
6. 激活失败自动回滚到上一个 `ACTIVE`，接口返回 `40901` 并附原版本号。

**兼容/回滚点**：`aps.dsl.legacy-singular=false` 关闭单数别名；`constraint_dsl` 停用后解析器退回「仅内置 8 条默认规则」，策略表 `strategy_rule` 不受影响。

### 24.2 C20 异步求解生命周期、取消与 STOMP 进度（唯一写者 W3 `dev-SE3`）

**状态机**（对外枚举，`SolveTaskStatus`）：

`PENDING → RUNNING → SOLVED | FAILED | CANCELLED | TIMEOUT`

- **数据库映射（重要）**：`V1__init.sql` 的 `solve_task.status` CHECK 为
  `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`。M2 **不改 CHECK、不加迁移**：
  **DB 与 API 统一使用这六个值**，`SUCCESS` 已废弃；C1 中「持久层存 `SUCCESS`、API 暴露
  `SOLVED`」的过渡期表述自本节起作废。状态映射集中在服务层，控制器不再做任何对外改写。
- **单一响应形状**：`POST /solve-tasks` **一律**返回 `SolveTaskDetail`；`ScheduleService.submit`
  的同步/异步只是内部执行方式，不改变响应结构。`application.yml` 中的
  `aps.solve.async-enabled` 配置项与“同步返回 C1 `SolveResult` / 异步返回 `SolveTaskDetail`”
  的双形状分支均已删除；执行模式仅由服务内部实现决定（默认异步），对外不可配置形状。
  异步受理时状态为 `PENDING/RUNNING`，调用方轮询 `GET /solve-tasks/{id}` 到终态。
- **终态任务快照**：`SolveTaskDetail.tasks` 在 `status=SOLVED` 时返回该 `sandboxId` 当前
  `deleted=false` 的 task 快照（`orderNo/resourceCode/startTime/endTime`），其余状态为空数组。
  快照为场景维度（同一 sandbox 可能含其他会话订单的既有任务），不是“本次请求订单”子集。
- 同一 `sandboxId` 同时只允许 1 条 `RUNNING`；超出的提交保持 `PENDING` 排队。

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `POST /solve-tasks` | 提交；`engine` 缺省 `timefold`。**一律返回 `SolveTaskDetail`**（C20）：状态为六值枚举，`SOLVED` 时 `tasks[]` 为场景任务快照；同步/异步只是内部执行方式，不影响响应结构 |
| `GET /solve-tasks/{id}` | **事实源**；返回 `SolveTaskDetail`，终态含 `tasks[]` |
| `POST /solve-tasks/{id}/cancel` | 仅 `PENDING/RUNNING` 可取消；其他状态 `40901` |
| `POST /solve-tasks/incremental` | 见 C21 |

**`phase` 枚举（与 `status` 分离；唯一口径，见下表）**：

`phase ∈ {QUEUED, ASSEMBLING, SOLVING, PERSISTING, DONE, FAILED, CANCELLED, TIMEOUT}`

| phase | 出现时机 | 对应 `status` |
|---|---|---|
| `QUEUED` | 已受理、尚未开始（`PENDING`）；无进度值时的默认值 | `PENDING` |
| `ASSEMBLING` | 装配求解问题 | `RUNNING` |
| `SOLVING` | 求解中 | `RUNNING` |
| `PERSISTING` | 结果落库 | `RUNNING` |
| `DONE` | 求解成功且结果已落库 | `SOLVED` |
| `FAILED` | 求解失败 / 引擎异常 / 装配校验失败（`42200`） | `FAILED` |
| `CANCELLED` | 用户取消 | `CANCELLED` |
| `TIMEOUT` | 超时（看门狗或引擎超时） | `TIMEOUT` |

- **禁止混用两套值**：`phase` 不得出现 `INITIAL/OPTIMIZATION/FINALIZING`（那是 C19 策略规则的 `phase`，与求解任务无关），也不得出现 `PENDING/RUNNING/SOLVED`（那些是 `status` 值）。`status=SOLVED` 时 `phase` 必须是 `DONE`。
- 实现不得再用“终态名兜底”（`metrics.getOrDefault("phase", status.name())` 直接落 `SOLVED` 就是 W3 实测到的错值）；缺失时按上表映射，`PENDING` 一律归 `QUEUED`。

`SolveTaskDetail` 在 M1 schema 上新增：`phase`（上表枚举）、`resultTaskId`（可空，`SOLVED` 时等于 `taskId`）、`degraded`（boolean，见 C28）、`tasks[]`（`SOLVED` 时的场景任务快照，其余状态为空数组）。

**序列化约定（`GET /solve-tasks/{id}` 与 STOMP 通用）**：

1. **声明的字段一律输出**：`null` 表示“该阶段无此值”，**不是“字段不存在”**。禁止用键存在性判断业务状态（`overrides.primaryResourceId` 的 `INHERIT/FLOAT` 是存储层三态，不适用于进度报文）。
2. 只在特定阶段有值的字段**始终存在**，取不到时为 `null`：`resultTaskId` 仅 `SOLVED` 有值；`bestScore` 求解开始前为 `null`；`errorCode`/`errorMessage` 成功时为 `null`；`finishedAt` 未结束为 `null`。
3. 实现必须对 `SolveTaskDetail`/`SolveProgress` 显式 `@JsonInclude(ALWAYS)`；全局 `spring.jackson.default-property-inclusion: non_null` 不得作用于这两个报文。
4. `metrics` 是开放对象，键集合不冻结，前端不得依赖其键存在性。

**构建制品验收（重要）**：凡涉及进度/推送的验收（`phase`、STOMP 帧、`tasks[]`），必须在**由当前源码构建出的制品**上执行，不得使用正在运行的旧实例（旧 jar 可能仍发 `phase=SOLVED`）。结论“已修复”必须同时给出构建命令、启动的制品路径/命令与真实报文片段；“源码通过单测”不等于“运行中的制品通过”。

**STOMP 进度**：

- 端点：`/ws/solve-tasks/{id}`（STOMP over WebSocket，`{id}` 为 `solve_task` BIGSERIAL）。
- 订阅：`/topic/solve-tasks/{id}`；连接成功后服务端立即推送一条当前快照。
- 消息体（`SolveProgress`）：

```json
{
  "taskId": 123,
  "status": "RUNNING",
  "progress": 0.42,
  "phase": "SOLVING",
  "bestScore": "-12hard/-345soft",
  "elapsedMs": 1830,
  "resultTaskId": null,
  "errorCode": null,
  "message": "求解中，已耗时 1830 ms"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `phase` | string | 上表枚举；始终输出 |
| `progress` | number | 0–1；`PENDING=0`，终态=1 |
| `bestScore` | string | Timefold 分数文本；未知为 `null`（键仍存在） |
| `resultTaskId` | number \| null | 仅 `SOLVED` 有值，其余为 `null` |
| `errorCode` | string \| null | 失败/超时原因码（`ENGINE_ERROR`/`TIMEOUT`/`FAILED`/`CANCELLED`）；成功为 `null`。**不是业务错误码整数** |
| `message` | string \| null | 面向用户的说明（如“求解中，已耗时 N ms”） |

- 推送节流：同状态变化立即推送，`progress/bestScore` 最多 1 次/秒；终态必推一次且含 `resultTaskId`。
- `degraded` **不在 STOMP 消息中**（实现无该字段），降级信息由 `GET /solve-tasks/{id}` 的 `degraded`/`metrics` 提供。
- **与轮询关系**：`GET /solve-tasks/{id}` 是唯一事实源；STOMP 只推送、不替代查询，不重放历史消息。前端断线后回退 2 秒轮询，重连以快照为准。
- 回滚点 `aps.solve.progress-push=stomp|poll`：设为 `poll` 时不注册 WS 端点，前端仅轮询；`50010` 只用于 WS 通道故障，不影响查询。

### 24.3 C21 增量重排与锁定语义（唯一写者 W4 `dev-SE4`）

**端点**：`POST /solve-tasks/incremental`

```json
{
  "baseSolveTaskId": 120,
  "affectedTaskIds": [1001, 1002],
  "sandboxId": "00000000-0000-0000-0000-000000000401",
  "reason": "设备故障重排"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `baseSolveTaskId` | 是 | 作为 `startingSolution` 的基准解 |
| `affectedTaskIds` | 否 | 为空表示该基准解全部非冻结任务 |
| `sandboxId` | 否 | 缺省 BASELINE |
| `reason` | 否 | 写入新版本 `reason` |

冻结规则：

1. 增量求解**写入新 `solve_task` 行**，并在 C24 计划域生成新 `plan_version`（`DRAFT`）；**禁止覆盖已 `PUBLISHED` 版本的 `task` 行**。
2. 已发布且已执行（`IN_PROGRESS/COMPLETED`）的任务不得被增量结果修改，冲突返回 `40903`。
3. `TaskAssignment` 新增：`lockType`（`NONE/TIME_WINDOW/RESOURCE/FROZEN`）、`lockedFrom/lockedTo`（`lockType=TIME_WINDOW`/`FROZEN` 时的闭区间，等价本文早期称法 `pinnedWindow{from,to}`，落地字段名为 `lockedFrom/lockedTo`）、`lockedResourceIds[]`（`lockType=RESOURCE` 时必填）。
4. 优先级：`FROZEN` > `lockType` > M1 的 `frozen:boolean`（仅为兼容别名，等价 `lockType=FROZEN`）。
5. 求解器必须保证 `TIME_WINDOW` 任务落在 `[from,to]` 内；`RESOURCE` 任务的**主资源必须属于 `lockedResourceIds`**（单元素集合=固定，多元素=白名单内可换，不是“主资源必须原样不变”）。若该任务主资源已被固定（`overrides.primaryResourceId` 非空），`RESOURCE` 锁不改变结果——这是 C15 20.3.1.2 说明的叠加结果，不是锁失效。

**锁定分层与冲突判定（订单冻结 `freezeLock` vs 任务锁，C23 反向引用本节）**：

判定顺序 `FROZEN(task.status) > task.lock_type > 订单 freezeLock` **只是判定先后**，不是"高优先级可以覆盖低优先级"——三层全部是硬约束，任何一层都不得被另一层或人工调整放宽。

1. **订单级与任务级同时生效，取交集**：任务级可以比订单级**更窄**（订单锁窗 `10:00–12:00`、任务锁 `10:30–11:00` 合法），**不得更宽**（任务锁 `08:00–09:00` 而订单锁 `10:00–12:00` 非法）；资源同理，任务锁的资源集合必须是订单锁定资源集合的**子集**。
2. **空交集 = 不可行**：在 `POST /tasks/{id}/adjust` 请求阶段直接拒绝（fail fast），不得留到求解阶段才发现。
3. **错误码 `42201`**（语义与代码见 C27/24.9）：响应 `message` 必须**同时列出**冲突的订单约束（`orderNo` + `freezeLock{from,to,resourceCodes}`）与任务约束（`taskId` + `lockType/lockedResourceIds`），让人知道该改哪一层。
4. **绝不允许静默放宽任何一层**：不允许"订单优先、任务锁作废"，也不允许"手工优先、订单冻结作废"。求解阶段若仍发现冲突，按 C28 走失败/降级标记，不得静默放宽。
5. **`FROZEN` 粒度（2026-11-04 澄清，W4 实现依据）**：
   - **任务级 `task.status='FROZEN'`（及 `task.lock_type<>'NONE'`）**：只钉住**该任务本身**（固定开始/结束/主资源）；**同订单的其他任务仍可重排**，必须作为固定赋值（基准解快照）进入增量题面，结果不得改动其时间与资源。
   - **订单级 `orders.freeze_status`（C23 四态）**：约束该订单的**全部任务**，属订单语义；订单冻结时该订单全部任务按 `freezeLock` 固定/不可重排。
   - **关系**：订单级是外层约束，任务级是内层约束，**取交集、互不替代**；订单解冻不自动解除任务级 `FROZEN`，任务解冻也不改变订单冻结。订单未冻结时，订单内未被任务级锁定的任务正常参与增量重排。
6. **禁止“整单排除”实现**：不得把 `task.lock_type<>'NONE' OR task.status='FROZEN' OR orders.freeze_status<>'UNFROZEN'` 统一映射为“排除整个 `order_id`”（即 `findLockedOrderIds` 现有写法，W4 需改造）。正确判定为按任务区分：任务级命中 → 只固定该任务；仅订单级命中 → 固定该订单全部任务。`affectedTaskIds` 仍表示“可重排任务”，被固定任务不进入可变集合，但**必须保留在基准解快照中**。
7. **`TIME_WINDOW` 为闭区间（权威口径）**：任务时间必须满足 `start >= lockFrom && end <= lockTo`，两端恰好命中均合法（W2 实测 `hardScore=0`）。
   - `V5__task_lock.sql` 中 `locked_to` 的列注释“结束（不含）”为**历史遗留、已过时**，以本条为准；**不得修改 V5 本体**（已应用，改注释即触发 Flyway 校验和不一致）。
   - **零长度窗（`from == to`）** 被 `CHECK (locked_to > locked_from)` 拒绝，属**预期行为**：调用方应在请求阶段返回 **400 + `40000`**，不得变成 500/内部错误；若需修注释，只能新增迁移 `COMMENT ON COLUMN task.locked_to IS ...`。

**兼容/回滚点**：`lockType` 缺省 `NONE`，旧客户端只传 `frozen` 时行为与 M1 一致；`aps.schedule.lock-enabled=false` 时退化为布尔冻结。

### 24.4 C22 甘特人工调整与撤销重做（唯一写者 W4 `dev-SE4`）

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `POST /tasks/{id}/adjust` | 人工调整；`dryRun=true` 只校验不落库 |
| `POST /tasks/adjustments/{adjustmentId}/undo` | 撤销最近一次（按任务栈） |
| `POST /tasks/adjustments/{adjustmentId}/redo` | 重做 |
| `GET /tasks/{id}/adjustments` | 调整历史（分页） |

**`TaskAdjustRequest`**：

```json
{
  "operation": "MOVE",
  "resourceCode": "R-001",
  "secondaryResourceCodes": [],
  "startTime": "2026-11-05T08:00:00Z",
  "lockFrom": null,
  "lockTo": null,
  "lockedResourceCodes": [],
  "reason": "插单",
  "version": 3,
  "dryRun": false
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `operation` | 是 | `MOVE/LOCK/FREEZE/UNFREEZE`；`SPLIT/MERGE` M2 冻结枚举但返回 `50100` |
| `version` | 是 | 与 `task.version` 比对，过期 `40901` |
| `dryRun` | 否 | 默认 `false` |
| `lockFrom/lockTo` | `LOCK` 时 | 时间窗；`lockTo > lockFrom` |
| `lockedResourceCodes` | `LOCK` 时可选 | 为空表示仅锁时间 |

- 响应 `TaskAdjustResult{adjustmentId, applied, dryRun, task, conflicts[]}`；`conflicts[]` 每项 `{type, message, relatedTaskIds}`。
- `FROZEN` 任务拒绝 `MOVE`（`42201`）；`FREEZE` 必须带 `reason`。
- 错误码：`40901` 版本冲突、`42201` 不可行（日历/依赖/资源冲突，以及**任务锁与订单 `freezeLock` 冲突**——判定与取交集规则见 C21/24.3，请求阶段 fail fast，message 同时列出订单约束与任务约束）、`40300` 无调整权限（M2 为占位，默认放行，M3 接 RBAC）。
- 调整记录写 `task_adjustment`（V1 已建表）；撤销/重做栈每任务最多 50 步。

### 24.5 C23 订单优先级、四态冻结与释放/开工（唯一写者 W5 `dev-BD5`）

| 方法/路径 | Body | 说明 |
|---|---|---|
| `PUT /orders/{orderNo}/freeze` | `{freezeStatus, lockFrom?, lockTo?, lockedResourceCodes?, version}` | 四态冻结；`UNFROZEN` 即解冻，无独立 `/unfreeze` |
| `PUT /orders/{orderNo}/priority` | `{priority, version}` | `URGENT/HIGH/NORMAL/LOW` |
| `PUT /orders/{orderNo}/release` | `{reason?, version?}`（body 可空） | **M2 纳入**：`DRAFT/CONFIRMED → RELEASED`，同时自动冻结该订单全部任务 |
| `PUT /orders/{orderNo}/start` | `{version}` | `RELEASED → IN_PROGRESS`，同样自动冻结 |

冻结校验（按 W5 BD-02 实际实现定稿）：

1. `LOCKED_TIME_WINDOW` 必须给 `lockFrom/lockTo` 且 `to > from`；**缺任一或 `from == to` → HTTP 400 + `40000`**（不是 422）。时间窗为**闭区间 `[from,to]`**（权威口径见 C21 §24.3 第 7 条；V5 的列注释已过时，不得改 V5）。
2. `LOCKED_RESOURCE` 必须给 `lockedResourceCodes[]`，逐项 `trim`、丢弃空串并去重后非空；仍为空 → `42200`。
3. `UNFROZEN/FROZEN` 不得携带锁字段；切换时**自动清空锁信息**（幂等，不报错）。
4. 锁信息不新增列，统一落 `orders.ext_attrs.freezeLock`（结构 `{from,to,resourceCodes}`）；`OrderView` 新增只读字段 `freezeLock`，空值时为 `null`。
5. 自动冻结写入 `task.lock_type='FROZEN'`（V5），范围=该订单当前全部任务；解冻必须人工显式调用 `/freeze {freezeStatus:UNFROZEN}`。
6. 最小审计：每次状态变更写 `audit_log(action, target_type='ORDER', target_id, operator, before_value, after_value, occurred_at)`；M2 不做全量审计中心。
7. 乐观锁：`version` 与 `orders.version` 不一致返回 `40901`；非法状态流转（如 `COMPLETED → RELEASED`）返回 `42200`。
8. `/release` 幂等：重复释放返回 200，`version` **不变**，不重复写审计；状态不允许释放 → `422/42200`。
9. 优先级只影响软约束权重（`PRIORITY_RESPECT`），不改硬约束；`URGENT` 不提供抢占/插队语义。
10. 订单级 `freezeLock` 与任务级锁同时生效、取交集；空交集 `42201`，判定规则见 C21/24.3，**不得以订单优先级覆盖任务锁，也不得反过来**。

### 24.6 C24 计划版本发布/回滚与快照（唯一写者 W5 `dev-BD5`）

| 方法/路径 | Body | 说明 |
|---|---|---|
| `POST /plans/{id}/publish` | `{reason, approver}` | `id` = `plan_version.id`（UUID） |
| `POST /plans/{id}/rollback` | `{targetVersionNo, reason}` | 生成**新版本**，不物理回退 |
| `GET /plans/{id}` | — | 版本详情与任务数 |

冻结：

1. `plan_version` 字段：`id/tenant/sandbox_id/plan_no/version_no/status/snapshot JSONB/published_at/published_by/approver/rollback_of/reason/task_count/version/审计字段`；`plan_no` 形如 `PLAN-YYYYMMDD-NNN`，租户内唯一；`version_no` 从 1 递增。
   - **`approver` 为契约字段（V3 建列）**：可选 `varchar(64)`；`POST /plans/{id}/publish` 未传时记为发布操作人（`published_by`），操作人也不可得时置空 `NULL`；回滚生成的新版本沿用请求操作人，不继承原版本 `approver`。
2. `status` 枚举 `DRAFT/REVIEW/PUBLISHED/ARCHIVED`（V3 CHECK）。
3. 发布：`snapshot` 固化当次全部任务（不可变），并回填 `task.plan_version_id`；重复发布同一 `plan_version` 幂等返回 200。
4. 回滚：新建 `plan_version`（`status=PUBLISHED`、`rollback_of=原版本`），把任务指向新版本；**已执行任务（`IN_PROGRESS/COMPLETED`）存在时拒绝，返回 `40903`**（新增，见 C27）。
5. MES 下发仅占位（`50100`），M2 不做外部系统推送。
6. `planId` 一律使用 `plan_version.id`，**不再用 sandboxId 充当计划 id**。
7. **占位退役（非契约内容，实施备注）**：`aps-web/.../controller/contract/GanttContractController` 的 `/plans/{id}/publish`、`/plans/{id}/rollback` 两个 501 占位已授权 W5 在 BD-03 落地时删除，避免与真实 Controller 产生 Ambiguous mapping。删除后本文件的端点定义仍以此处为准，OpenAPI 的 `x-implementation-status` 由 W5 同步更新。

### 24.7 C25 库存落表与 MRP 齐套（唯一写者 W6 `dev-MRP`）

**V2 表**（`db/migration/V2__inventory.sql`）：

| 表 | 关键列 | 约束 |
|---|---|---|
| `inventory` | `id/tenant_id/material_id/warehouse_code/on_hand_qty/safety_stock/snapshot_at/version/审计字段` | `(tenant_id, material_id, warehouse_code) WHERE deleted=false` 唯一；`on_hand_qty >= 0`；`safety_stock >= 0` |
| `inventory_transit` | `id/tenant_id/material_id/qty/expected_date/source_type/status/审计字段` | `qty > 0`；`(tenant_id, material_id, expected_date)` 索引；`status IN (OPEN,ARRIVED,CANCELLED)` |

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `PUT /inventory` | 批量 upsert；body `{items:[{materialCode,warehouseCode,onHandQty,safetyStock,snapshotAt}]}`；返回 `{accepted, rejected}` |

- `PUT /inventory` 按 `materialCode + warehouseCode` upsert，`snapshotAt` 缺省当前 UTC；物料不存在 `40400`、数量为负 `42200`、并发版本冲突 `40901`。
- **M2 不做库存维护页**（归 M3）；该端点供 Excel/脚本灌数，W9 的 `POST /{entity}/import` 可调用同一服务。
- **`POST /mrp/analysis` 请求增量**：`blocking`（boolean，默认 `true`）——`true` 时齐套未通过阻止排产提交（`42200` + 缺料明细）。
- **`MrpAnalysis` 响应增量**：

```json
{
  "sandboxId": "...",
  "analyzedAt": "2026-11-05T02:00:00Z",
  "status": "FULL|PARTIAL|SHORTAGE",
  "blocking": true,
  "orders": [
    { "orderNo": "SO-001", "status": "SHORTAGE", "shortageCount": 2 }
  ],
  "shortages": [
    {
      "materialCode": "M-001",
      "requiredQty": 120,
      "onHandQty": 40,
      "transitQty": 30,
      "safetyStock": 10,
      "availableQty": 60,
      "shortageQty": 60,
      "earliestRequiredAt": "2026-11-06T00:00:00Z",
      "affectedOrderNos": ["SO-001"]
    }
  ]
}
```

- `availableQty = onHandQty + transitQty - safetyStock`，下限 0；`transitQty` 只计 `status=OPEN` 且 `expected_date` 在需求时点前。
- `FULL` 表示全部需求满足；`PARTIAL` 部分缺料；`SHORTAGE` 存在关键缺料。只要 `status != FULL` 且 `blocking=true`，排产提交返回 `42200`。
- MRP 需求来自订单生效工艺路线的 BOM 多级展开，按 `requiredAt` 倒排；递归展开需检测环（`42200`）。

**兼容/回滚点 R2**：`aps.mrp.inventory-enabled=false` 时 MRP 退回请求体快照模式；新增表可整体 `DROP`，不改既有表。

### 24.8 C26 Excel 导入导出（唯一写者 W9 `dev-QA2`）

| 方法/路径 | 说明 |
|---|---|
| `GET /{entity}/import/template` | 下载模板（xlsx），`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `POST /{entity}/import?dryRun=true` | 预览校验，返回逐行结果，不落库 |
| `POST /{entity}/import` | 执行导入；`Idempotency-Key` 必填 |
| `GET /{entity}/export` | 导出当前筛选结果 |

- 错误行结构：`{row, field, code, message}`；整体返回 `Result<ImportResult{total, success, failed, errors[]}>`。
- 上限：文件 ≤5MB、数据行 ≤5000；超限 `42200`。
- 拟定 9 类实体（PRD BR-INT-004-01 中 M2 可落地者）：`materials`、`boms`、`resources`、`calendars`、`customers`、`suppliers`、`orders`、`routings`、`operationTemplates`。
  - `换型矩阵`不在 M2（C12 降级），故不进清单；策略导入导出走 C19。
  - **该清单在 M2-BD-05 开工前由用户最终点名确认**（用户七项拍板未覆盖此项），点名后本表即冻结。
- 导入复用各域现有 Service 校验，不绕过业务规则；`dryRun` 与执行共用同一校验器。

### 24.9 C27 REST 认证分级、限流、幂等与错误码（唯一写者 W1 `dev-CONTRACT`）

**认证分级（按 profile）**：

| 模式 | 触发 | 规则 |
|---|---|---|
| `prod` | `spring.profiles.active=prod`（或显式 `mode=prod`） | **全模块强制 JWT**（`Authorization: Bearer`，HS256）；缺失/过期 `40100`，签名有效但缺少 `APS_USER` 角色 `40300` |
| `dev` | **显式开启**：`APS_SECURITY_MODE=dev`（或 `--aps.security.mode=dev`） | **仅写端点**（`POST/PUT/PATCH/DELETE`）强制认证；`X-API-Key` 直通（写密钥 200，读密钥 `40300`，缺失/错误 `40100`）；GET 放行 |
| `off` | **默认**（非 prod 且未显式配置）或 `APS_SECURITY_MODE=off` | 全放行，保证本地 demo/回归脚本（不带任何凭证）可整链跑通 |

- **默认值（冻结）**：不配置 `APS_SECURITY_MODE` 时，`prod` profile → `prod`；**其余（dev/test/无 profile）一律 `off`**。即"默认不挡人，prod 必挡人"。
- 覆盖开关：`APS_SECURITY_MODE=prod|dev|off`，优先级高于 profile 推断；本地要验证认证行为须显式开启 `dev`。
- 校验顺序：`TraceId → 认证 → 限流 → 幂等 → Controller`；认证失败不再消耗限流额度。
- 登录页/令牌签发属 **M3-FE-02**，M2 不提供 `/auth/login` 实现（保持 `50100`）；QA 联调用 API Key。
- API Key/JWT 密钥只从环境变量读取，禁止入库、入日志、入审计。

**限流**：

- 阈值：100 req/min，按 `tenantId + 调用方标识`（JWT `sub` 或 API Key 指纹）；`off` 模式默认关闭。
- 响应头：`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`（UTC 秒）。
- 超限：HTTP 429 + `code=42900`。
- M2 实现为单实例内存令牌桶；多实例需换 Redis（接口不变）。

**幂等**：沿用 `docs/api/conventions.md` §5（`Idempotency-Key` 8–128 位，重复 `40900`，失败自动释放）。M2 新增 V6 数据库唯一索引作为多实例兜底：`(tenant_id, sandbox_id, idempotency_key) WHERE idempotency_key IS NOT NULL`。

**错误码新增**：

| code | HTTP | 默认 message | 场景 |
|---|---|---|---|
| `40903` | 409 | 已发布的计划/任务不可回滚或覆盖 | 计划回滚遇已执行任务（C24）、增量覆盖已发布任务（C21） |
| `42201` | 422 | 调整不可行 | 甘特调整触发日历/依赖/资源冲突（C22）；**任务锁与订单 `freezeLock` 空交集冲突**（判定与取交集规则见 C21/24.3）；message 必须同时列出冲突的订单约束与任务约束 |

> `42201` 已在 `aps-common` 落地：`ErrorCode.ADJUSTMENT_INFEASIBLE`（`aps-common/src/main/java/com/hengce/aps/common/exception/ErrorCode.java`），HTTP 映射见 `RestExceptionHandler`（`UNPROCESSABLE_ENTITY`）；枚举名沿用，不改名以兼容 W4 已写入的引用。

其余沿用：`40100/40300/40400/40900/40901/42200/42900/50000/50010/50100`。

**分页**：`page ≥1`、`size` 默认 20 上限 200，超限按 200；`sort=field,asc|desc`，未知字段 `40000`。

- `GET /resources` 同样受 `size ≤ 200` 约束；服务端**不会**为超过 200 的 `size` 放大返回。
- 需要超过 200 条资源时的取数口径（按优先级）：
  1. **甘特/排产场景不枚举资源**：直接用 C4 `GET /gantt/resource` 行上的 `calendarId/calendarBound`（见 C4 增量），避免 N+1 与分页截断。
  2. **按 code 批量**：`GET /resources?codes=A,B,C`（逗号分隔，单次 ≤200 个 code，忽略 `page/size`），返回命中的完整资源，`total` = 实际命中数；超过 200 个 code 需分批。
  3. 无 `codes` 时按 `page` 递增翻页直到累计到 `total`（游标式取全量），不得依赖 `size > 200`。
- `codes` 与 `code` 同时出现时 `codes` 优先；两者都不传才走分页全量。

### 24.10 C28 引擎路由与降级标记（唯一写者 W3 `dev-SE3`）

- 引擎选择：请求 `engine` 优先，缺省 `timefold`；`ortools` 在 M2 仍是占位（`OrtoolsSolverAdapter` 返回 FAILED），不得对外宣称可用。
- **不新建规则引擎**。降级只覆盖：Timefold 抛异常、超时（终止后无可行解）、求解器不可用。
- `fallbackMode` 枚举**冻结为 `HARD_ONLY | PARTIAL`**；**M2 只产出 `HARD_ONLY`**，`PARTIAL` 预留给 M3（避免二次改契约）。
- 降级标记落 `solve_task.metrics`（JSONB）固定键：

| key | 类型 | 值 |
|---|---|---|
| `engineFallback` | boolean | `true` |
| `engineFallbackReason` | string | `TIMEOUT/ENGINE_ERROR/SOLVER_UNAVAILABLE` |
| `fallbackMode` | string | `HARD_ONLY`（M2 唯一取值） |
| `fallbackAt` | string | UTC ISO-8601 |
| `terminationReason` | string | 与 `SolveMetrics` 一致 |

- 接口透传：`GET /solve-tasks/{id}` 的 `metrics` 原样返回，并置 `degraded=true`；列表可返回摘要 `degraded`。
- DSL 校验失败**不降级**，返回 `42200`（PRD BR-SE-005-04）。
- 无任何可行解且引擎不可用：`status=FAILED`，`errorCode=50010`，仍写降级标记。
- 前端展示：进度/结果页显示黄色「降级」徽标 + tooltip 原因；**不得显示为绿色成功**；已发布计划保留 `degraded` 只读字段。
- 回滚点 `aps.solve.fallback-enabled=false`：异常/超时直接 `FAILED`，不产生降级标记；历史 `metrics` 键保留。

### 24.11 C29 订单甘特读接口（唯一写者 W4 `dev-SE4`）

`GET /gantt/order?sandboxId&from&to&orderNos`

| 参数 | 必填 | 说明 |
|---|---|---|
| `sandboxId` | 否 | 缺省 BASELINE `...0401` |
| `from` / `to` | 是 | ISO-8601 UTC，闭开区间 |
| `orderNos` | 否 | 逗号分隔；空=全部 |

响应 `data`：

```json
{
  "sandboxId": "...",
  "from": "2026-11-05T00:00:00Z",
  "to": "2026-11-06T00:00:00Z",
  "orders": [
    {
      "orderNo": "SO-001",
      "materialCode": "M-001",
      "dueDate": "2026-11-06",
      "priority": "URGENT",
      "freezeStatus": "UNFROZEN",
      "late": true,
      "lateMinutes": 120,
      "tasks": [
        { "id": 1001, "operationIndex": 10, "operationName": "SMT", "resourceCode": "R-001",
          "startTime": "...", "endTime": "...", "status": "PLANNED", "version": 1,
          "lockType": "NONE", "late": false }
      ]
    }
  ]
}
```

- `late = 任务 endTime > 订单 dueDate 当日结束（UTC 23:59:59）`；订单级 `lateMinutes` 为整数分钟，**仅 `late=true` 时给出**，`late=false` 时省略或 `null`，不得填 0 冒充。
- 任务复用 `GanttTask`（C4）；新增 `lockType`、`late` 两个可选字段，旧客户端忽略。
  - `lockType` 推导：M2 只由 `task.status` 推导 `FROZEN | NONE`（`FROZEN` 任务 → `FROZEN`，其余 → `NONE`）；V5 生效后改为回读 `task.lock_type`，可取值扩展为 `TIME_WINDOW/RESOURCE`，字段名与语义不变，不新增字段。
- 非分页结构；排序**固定为 `orderNo, operationIndex`**（稳定排序），**不按交期/开工时间排序**，`from/to` 只做窗口过滤不改排序。

### 24.12 本文件仍未冻结的 M2 项（如实登记）

| 项 | 状态 | 归属 |
|---|---|---|
| C26 的 9 类 Excel 实体最终名单 | 拟定，待用户点名 | W9 / 用户 |
| 真实换型矩阵表 `setup_matrix_entry` | 明确不进 M2（静态矩阵 + `setupMatrixFallback=true`） | 用户已拍板 |
| `SPLIT/MERGE` 调整语义 | 枚举已冻结，M2 返回 `50100` | W4 / M3 |
| `PARTIAL` 降级模式 | 枚举已冻结，M2 不产出 | W3 / M3 |
