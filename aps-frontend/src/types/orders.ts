/**
 * orders 域类型，字段对齐 docs/api/openapi/orders.yaml；
 * 枚举取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

export type OrderSource = 'ERP_SYNC' | 'MANUAL' | 'AGENT'

export type OrderPriority = 'URGENT' | 'HIGH' | 'NORMAL' | 'LOW'

export type OrderStatus =
  'DRAFT' | 'CONFIRMED' | 'SCHEDULED' | 'RELEASED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export type FreezeStatus = 'UNFROZEN' | 'FROZEN' | 'LOCKED_TIME_WINDOW' | 'LOCKED_RESOURCE'

export interface Order {
  orderNo: string
  materialCode: string
  qty: number
  status: OrderStatus
  freezeStatus: FreezeStatus
  dueDate: string
  priority: OrderPriority
  source: OrderSource
  customerId?: string | null
  routingId?: string | null
  remark?: string | null
  extAttrs?: Record<string, unknown>
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface OrderCreateRequest {
  orderNo: string
  source?: OrderSource
  materialCode: string
  qty: number
  dueDate: string
  priority?: OrderPriority
  customerId?: string | null
  routingId?: string | null
  remark?: string
  extAttrs?: Record<string, unknown>
}

/** 更新请求：仅 DRAFT 可编辑，version 用于乐观锁。 */
export interface OrderUpdateRequest {
  materialCode: string
  qty: number
  dueDate: string
  priority: OrderPriority
  customerId?: string | null
  routingId?: string | null
  remark?: string | null
  extAttrs?: Record<string, unknown>
  version: number
}

/** 列表筛选：全部为可选参数，空值由序列化层跳过。 */
export interface OrderQuery {
  orderNo?: string
  customerId?: string
  materialCode?: string
  status?: OrderStatus
  dueDateFrom?: string
  dueDateTo?: string
}

export interface OrderFreezeRequest {
  freezeStatus: FreezeStatus
  version: number
}

export interface AtpRequest {
  materialCode: string
  qty: number
  requestedDate: string
  customerId?: string | null
}

export interface AtpResult {
  feasible: boolean
  earliestDate: string
  leadTimeDays: number
  bottleneckResourceCode?: string | null
}

export type StrategyDirection = 'FORWARD' | 'BACKWARD' | 'BOTTLENECK'

/** 策略规则类型，对齐 strategy_rule.rule_type CHECK（HARD/SOFT）。 */
export type StrategyRuleType = 'HARD' | 'SOFT'

/** 策略规则阶段，对齐 strategy_rule.rule_phase CHECK（INITIAL/OPTIMIZATION）。 */
export type StrategyRulePhase = 'INITIAL' | 'OPTIMIZATION'

/** 策略规则明细，字段对齐 strategy_rule（db/migration/V1__init.sql 708-736）。 */
export interface StrategyRule {
  id?: string
  code: string
  name?: string | null
  phase: StrategyRulePhase
  type: StrategyRuleType
  weight: number
  params?: Record<string, unknown>
  enabled: boolean
  seqNo: number
}

/** 初排维度，对齐 BR-SS-001-01。 */
export type InitialSortDimension =
  | 'PRIORITY'
  | 'DUE_DATE'
  | 'CUSTOMER_LEVEL'
  | 'INVENTORY_STATUS'
  | 'RELEASE_TIME'

export type InitialSortDirection = 'ASC' | 'DESC'

/** 初排维度规则，对齐 PRD FR-SS-001 入参 `rules: [{dimension, direction, weight}]`。 */
export interface InitialDimensionRule {
  dimension: InitialSortDimension
  direction: InitialSortDirection
  weight: number
}

export interface Strategy {
  id: string
  name: string
  direction: StrategyDirection
  strategyVersion: number
  effectiveFrom: string
  effectiveTo?: string | null
  initialRules?: Record<string, unknown>
  optimizationRules?: Record<string, unknown>
  applyCondition?: Record<string, unknown>
  /** 结构化规则明细；以 strategy_rule 为准（schema.md 5.8）。 */
  rules?: StrategyRule[]
  version: number
}

export interface StrategyCreateRequest {
  name: string
  direction: StrategyDirection
  strategyVersion?: number
  effectiveFrom: string
  effectiveTo?: string | null
  initialRules?: Record<string, unknown>
  optimizationRules?: Record<string, unknown>
  applyCondition?: Record<string, unknown>
  rules?: StrategyRule[]
}

/** 策略更新：C19 `PUT /strategies/{id}`，rules 为全量替换，version 为乐观锁。 */
export interface StrategyUpdateRequest extends StrategyCreateRequest {
  rules: StrategyRule[]
  version: number
}

export interface StrategyDetail extends Strategy {
  rules: StrategyRule[]
}

export type SandboxType = 'BASELINE' | 'WHAT_IF' | 'ARCHIVED'

export interface Sandbox {
  id: string
  name: string
  type: SandboxType
  parentSandboxId?: string | null
  expiresAt?: string | null
  snapshot?: Record<string, unknown>
  version: number
  createdAt?: string
}

export interface SandboxCreateRequest {
  name: string
  type: SandboxType
  parentSandboxId?: string | null
  expiresAt?: string | null
  snapshot?: Record<string, unknown>
}

export interface SandboxChangedTask {
  taskId: number
  orderNo: string
  resourceCode: string
}

export interface SandboxCompare {
  sandboxId: string
  targetId: string
  dueDateDeltaMinutes: number
  utilizationDelta: number
  setupCountDelta: number
  changedTasks: SandboxChangedTask[]
}
