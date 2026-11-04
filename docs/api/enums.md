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
