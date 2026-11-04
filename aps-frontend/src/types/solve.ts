/**
 * solve 域类型，字段对齐 docs/api/openapi/solve.yaml。
 * 注意：提交求解的 engine 契约为小写 timefold/ortools/rule_based；
 * solve_task.engine 的库内 CHECK 取值为 TIMEFOLD/ORTOOLS/RULE_BASED（schema.md 4.1）。
 */

export type SolverEngine = 'timefold' | 'ortools' | 'rule_based'

export interface SolveSubmitRequest {
  taskNo?: string
  sandboxId?: string
  strategyId?: string | null
  engine?: SolverEngine
  orderNos?: string[]
  parameters?: Record<string, unknown>
}

export type SolveTaskStatus = 'PENDING' | 'RUNNING' | 'SOLVED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'

export interface SolveTaskDetail {
  id: number
  taskNo: string
  sandboxId: string
  strategyId?: string | null
  engine: SolverEngine
  status: SolveTaskStatus
  scoreHard?: number | null
  scoreSoft?: number | null
  /** C20：0~1 的求解进度，GET 为事实源。 */
  progress?: number | null
  phase?: string | null
  bestScore?: string | null
  elapsedMs?: number | null
  resultTaskId?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  metrics?: Record<string, unknown>
  errorMessage?: string | null
  createdAt?: string
}

/** C20 STOMP 进度消息体（/topic/solve-tasks/{id}）。 */
export interface SolveProgress {
  taskId: number
  status: SolveTaskStatus
  progress: number
  phase?: string | null
  bestScore?: string | null
  elapsedMs?: number | null
  resultTaskId?: number | null
  errorCode?: string | null
  message?: string | null
}

export type AgentEventType =
  'EQUIPMENT_DOWN' | 'MATERIAL_SHORTAGE' | 'ORDER_RUSH' | 'CAPACITY_ABNORMAL'

export interface AgentEventRequest {
  eventType: AgentEventType
  orderNo?: string | null
  resourceCode?: string | null
  occurredAt: string
  payload?: Record<string, unknown>
}

export interface NlToDslRequest {
  text: string
  sandboxId?: string | null
  context?: Record<string, unknown>
}

export interface NlToDslResult {
  dsl: Record<string, unknown>
  confidence: number
  warnings: string[]
}

export interface ExplainCause {
  type: string
  description: string
  evidence?: Record<string, unknown>
}

export interface ExplainResult {
  taskId: number
  summary: string
  causes: ExplainCause[]
  suggestions: string[]
}

export interface WhatIfRequest {
  sandboxId: string
  question: string
  parameters?: Record<string, unknown>
}

export interface WhatIfResult {
  answer: string
  appliedChanges: Record<string, unknown>[]
  impact?: Record<string, unknown>
}
