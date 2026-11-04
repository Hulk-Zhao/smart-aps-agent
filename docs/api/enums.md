# 枚举字典（M1）

> 来源：`db/migration/V1__init.sql` 的 `CHECK` 约束（行号为该文件当前行号）。
> 用途：前端常量、后端 Java 枚举、OpenAPI `enum` 三处共用。
> 规则：API 传输值使用本表大写字符串；新增值需同步更新本文件与 schema（迁移号段见 `docs/api/contract-freeze.md` 第 2 节）。

## 1. 字典表

| 域 | 列 | 取值 | schema 依据（V1 行号） |
|---|---|---|---|
| 租户 | `tenant.status` | `ACTIVE` / `SUSPENDED` | 41 |
| 用户 | `users.status` | `ACTIVE` / `DISABLED` / `LOCKED` | 70 |
| 客户 | `customer.priority` | `VIP` / `NORMAL` / `LOW` | 161 |
| 供应商 | `supplier.quality_grade` | `A` / `B` / `C`（可空） | 185 |
| 物料 | `material.type` | `FINISHED` / `SEMI` / `RAW` / `AUX` / `PACK` | 238 |
| BOM | `bom.status` | `DRAFT` / `ACTIVE` / `INACTIVE` | 274 |
| 资源 | `resource.type` | `PRIMARY` / `SECONDARY` / `HUMAN` / `TOOL` | 362 |
| 资源 | `resource.capacity_mode` | `UNIT_PER_HOUR` / `SECONDS_PER_UNIT` / `BATCH_SECONDS` | 363 |
| 日历 | `calendar.type` | `FACTORY` / `RESOURCE` / `SHIFT` | 402 |
| 日历 | `calendar.holidays[].kind` | `LEGAL` / `COMPANY` / `MAKEUP`（JSONB，无 CHECK） | 424 |
| 工艺路线 | `routing.status` | `DRAFT` / `ACTIVE` / `INACTIVE` | 487 |
| 工艺路线边 | `routing_edge.depend_type` | `FS` / `SS` / `FF` | 549 |
| 订单 | `orders.source` | `ERP_SYNC` / `MANUAL` / `AGENT` | 586 |
| 订单 | `orders.priority` | `URGENT` / `HIGH` / `NORMAL` / `LOW` | 588 |
| 订单 | `orders.status` | `DRAFT` / `CONFIRMED` / `SCHEDULED` / `RELEASED` / `IN_PROGRESS` / `COMPLETED` / `CANCELLED` | 589 |
| 订单 | `orders.freeze_status` | `UNFROZEN` / `FROZEN` / `LOCKED_TIME_WINDOW` / `LOCKED_RESOURCE` | 590 |
| 场景 | `sandbox.type` | `BASELINE` / `WHAT_IF` / `ARCHIVED` | 620 |
| 排产任务 | `task.status` | `PLANNED` / `FROZEN` / `IN_PROGRESS` / `COMPLETED` | 659 |
| 策略 | `strategy.direction` | `FORWARD` / `BACKWARD` / `BOTTLENECK` | 695 |
| 策略规则 | `strategy_rule.phase` | `INITIAL` / `OPTIMIZATION` | 728 |
| 策略规则 | `strategy_rule.type` | `HARD` / `SOFT` | 729 |
| 求解任务 | `solve_task.engine` | `TIMEFOLD` / `ORTOOLS` / `RULE_BASED` | 765 |
| 求解任务 | `solve_task.status` | `PENDING` / `RUNNING` / `SOLVED` / `FAILED` / `CANCELLED` / `TIMEOUT` | 766 |
| 任务调整 | `task_adjustment.adjust_type` | `SPLIT` / `MERGE` / `MOVE` / `LOCK` / `FREEZE` / `UNFREEZE` | 825 |

## 2. API 专用枚举（非 schema CHECK）

| 位置 | 取值 | 说明 |
|---|---|---|
| `solver-api` `SolverEngine` | 外部小写 `timefold` / `ortools`；内部预留 `rule_based` | 兼容现有 `POST /solve-tasks`；落库转 `TIMEFOLD` / `ORTOOLS` / `RULE_BASED` |
| `solve.yaml` `SolveStatus`（已废弃） | `SUCCESS` / `FAILED` / `UNSUPPORTED` | C20 起 API 与 DB 统一六值枚举（`PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`）；`SUCCESS` 不再对外暴露，`POST /solve-tasks` 只返回 `SolveTaskDetail` |
| `SolveTaskDetail.phase` / `SolveProgress.phase`（C20） | `QUEUED` / `ASSEMBLING` / `SOLVING` / `PERSISTING` / `DONE` / `FAILED` / `CANCELLED` / `TIMEOUT` | 与 `status` 分离的进度阶段；`PENDING→QUEUED`、`SOLVED→DONE`；禁止与 `INITIAL/OPTIMIZATION/FINALIZING` 混用 |
| `gantt.yaml` `TaskStatus` | `PLANNED` / `FROZEN` / `IN_PROGRESS` / `COMPLETED` | 与 `task.status` 一致 |

## 3. 机器可读 JSON

```json
{
  "tenant.status": ["ACTIVE", "SUSPENDED"],
  "users.status": ["ACTIVE", "DISABLED", "LOCKED"],
  "customer.priority": ["VIP", "NORMAL", "LOW"],
  "supplier.quality_grade": ["A", "B", "C"],
  "material.type": ["FINISHED", "SEMI", "RAW", "AUX", "PACK"],
  "bom.status": ["DRAFT", "ACTIVE", "INACTIVE"],
  "resource.type": ["PRIMARY", "SECONDARY", "HUMAN", "TOOL"],
  "resource.capacity_mode": ["UNIT_PER_HOUR", "SECONDS_PER_UNIT", "BATCH_SECONDS"],
  "calendar.type": ["FACTORY", "RESOURCE", "SHIFT"],
  "calendar.holidays[].kind": ["LEGAL", "COMPANY", "MAKEUP"],
  "routing.status": ["DRAFT", "ACTIVE", "INACTIVE"],
  "routing_edge.depend_type": ["FS", "SS", "FF"],
  "orders.source": ["ERP_SYNC", "MANUAL", "AGENT"],
  "orders.priority": ["URGENT", "HIGH", "NORMAL", "LOW"],
  "orders.status": ["DRAFT", "CONFIRMED", "SCHEDULED", "RELEASED", "IN_PROGRESS", "COMPLETED", "CANCELLED"],
  "orders.freeze_status": ["UNFROZEN", "FROZEN", "LOCKED_TIME_WINDOW", "LOCKED_RESOURCE"],
  "sandbox.type": ["BASELINE", "WHAT_IF", "ARCHIVED"],
  "task.status": ["PLANNED", "FROZEN", "IN_PROGRESS", "COMPLETED"],
  "strategy.direction": ["FORWARD", "BACKWARD", "BOTTLENECK"],
  "strategy_rule.phase": ["INITIAL", "OPTIMIZATION"],
  "strategy_rule.type": ["HARD", "SOFT"],
  "solve_task.engine": ["TIMEFOLD", "ORTOOLS", "RULE_BASED"],
  "solve_task.status": ["PENDING", "RUNNING", "SOLVED", "FAILED", "CANCELLED", "TIMEOUT"],
  "solve_task.phase": ["QUEUED", "ASSEMBLING", "SOLVING", "PERSISTING", "DONE", "FAILED", "CANCELLED", "TIMEOUT"],
  "task_adjustment.adjust_type": ["SPLIT", "MERGE", "MOVE", "LOCK", "FREEZE", "UNFREEZE"],
  "solver.engine.api": ["timefold", "ortools", "rule_based"],
  "solve.result.status.legacy": ["SUCCESS", "FAILED", "UNSUPPORTED"]
}
```

## 4. 状态流转约束

- `orders.status`：`DRAFT → CONFIRMED → SCHEDULED → RELEASED → IN_PROGRESS → COMPLETED`；`CANCELLED` 可从任意非 `COMPLETED` 状态进入。
- `orders.freeze_status`：`UNFROZEN ↔ FROZEN`；`LOCKED_TIME_WINDOW` / `LOCKED_RESOURCE` 为更细粒度锁定，解除后回到 `UNFROZEN`。
- `task.status`：`PLANNED ↔ FROZEN`；`PLANNED/FROZEN → IN_PROGRESS → COMPLETED`。
- `solve_task.status`：`PENDING → RUNNING → SOLVED/FAILED/TIMEOUT`；`PENDING/RUNNING → CANCELLED`。
- `bom.status` / `routing.status`：同一父件/产品仅允许 1 个 `ACTIVE`（见 `contract-freeze.md` C8）。

## 5. M3 增量枚举（D0 冻结，见 `contract-freeze.md` §25）

> 本节只增不改：第 1–4 节与既有字典**一字不动**。下列值不新增 schema CHECK 的，仅用于 API/Java 枚举。

| 位置 | 取值 | 依据/说明 |
|---|---|---|
| `PermissionCode`（API/Java） | `MD_READ` / `MD_WRITE` / `PR_READ` / `PR_WRITE` / `OM_READ` / `OM_WRITE` / `OM_ATP` / `ORDER_FREEZE` / `SS_READ` / `SS_WRITE` / `SE_SOLVE` / `SCHEDULE_ADJUST` / `SCHEDULE_PUBLISH` / `SYS_USER` / `SYS_ROLE` / `SYS_AUDIT` / `AGENT_EVENT` / `AGENT_CONFIRM` | 等于 V1 `permission.code` 18 值（C30）；**不得新增/改名** |
| `RoleCode`（API/Java） | `ADMIN` / `PLANNER` / `PROCESS_ENGINEER` / `SALES` / `VIEWER` | V1 种子角色码；不可删除、不可改码 |
| `users.status`（API 传输） | `ACTIVE` / `DISABLED` / `LOCKED` | 沿用第 1 节；`LOCKED` + `locked_until` 由登录失败策略写入（C30） |
| `AuditEventType`（API/Java） | `ORDER_FROZEN` / `ORDER_PRIORITY_CHANGED` / `ORDER_RELEASED` / `AUTH_LOGIN` / `AUTH_LOGIN_FAILED` / `USER_CREATED` / `USER_UPDATED` / `ROLE_CREATED` / `ROLE_PERMISSION_CHANGED` / `PLAN_PUBLISHED` / `PLAN_ROLLBACK` / `PLAN_DISPATCHED` / `SANDBOX_CREATED` / `SANDBOX_APPLIED` / `IMPORT_EXECUTED` / `TASK_ADJUSTED` | 前 3 个为存量，**不得改名**（C31）；其余为 M3 新增 |
| `AuditResourceType`（API/Java） | `USER` / `ROLE` / `ORDER` / `PLAN` / `SANDBOX` / `TASK` / `IMPORT` | C31 |
| `SandboxCompare.changeType`（API） | `ADDED` / `REMOVED` / `MOVED` | C32 新增字段（yaml 待同步） |
| `SandboxType`（API 入参） | 只接受 `WHAT_IF` | 创建/更新入参；`BASELINE`/`ARCHIVED` 由 apply 产生，传值 `42200`（C32） |
| `NotificationChannel`（API/Java） | `EMAIL` / `SMS` / `FEISHU` | C33；不做 `WEBHOOK` |
| `DispatchStatus`（API） | `QUEUED` / `SENT` / `PARTIAL` / `FAILED` | C33 聚合状态；单目标内部态 `PENDING/SENT/FAILED` 不对外 |
| `ImportMode`（API/Java） | `preview` / `execute` / `async` | 前两值为存量（C26），`async` 为 C39 新增 |
| `ImportJobStatus`（API/DB） | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED` | C39；`FAILED` 必 `committed=false` |
| `fallbackMode`（`solve_task.metrics`） | `HARD_ONLY` / `PARTIAL` | 沿用 C28；M3 起可产出 `PARTIAL`，**终态仍为 `FAILED`**（C37） |

```json
{
  "permission.code": ["MD_READ", "MD_WRITE", "PR_READ", "PR_WRITE", "OM_READ", "OM_WRITE", "OM_ATP", "ORDER_FREEZE", "SS_READ", "SS_WRITE", "SE_SOLVE", "SCHEDULE_ADJUST", "SCHEDULE_PUBLISH", "SYS_USER", "SYS_ROLE", "SYS_AUDIT", "AGENT_EVENT", "AGENT_CONFIRM"],
  "role.code": ["ADMIN", "PLANNER", "PROCESS_ENGINEER", "SALES", "VIEWER"],
  "audit.action": ["ORDER_FROZEN", "ORDER_PRIORITY_CHANGED", "ORDER_RELEASED", "AUTH_LOGIN", "AUTH_LOGIN_FAILED", "USER_CREATED", "USER_UPDATED", "ROLE_CREATED", "ROLE_PERMISSION_CHANGED", "PLAN_PUBLISHED", "PLAN_ROLLBACK", "PLAN_DISPATCHED", "SANDBOX_CREATED", "SANDBOX_APPLIED", "IMPORT_EXECUTED", "TASK_ADJUSTED"],
  "audit.resource_type": ["USER", "ROLE", "ORDER", "PLAN", "SANDBOX", "TASK", "IMPORT"],
  "sandbox.compare.change_type": ["ADDED", "REMOVED", "MOVED"],
  "notification.channel": ["EMAIL", "SMS", "FEISHU"],
  "dispatch.status": ["QUEUED", "SENT", "PARTIAL", "FAILED"],
  "import.mode": ["preview", "execute", "async"],
  "import_job.status": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"],
  "solve_task.metrics.fallbackMode": ["HARD_ONLY", "PARTIAL"]
}
```

**M3 明确不新增**：`solve_task.status` 不加 `PARTIAL`（C37）；`sandbox.type` 不加新值；`orders.status`/`task.status` 不变。
