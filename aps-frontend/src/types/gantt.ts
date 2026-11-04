/**
 * gantt 域类型，字段对齐 docs/api/openapi/gantt.yaml；
 * task.status 取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

import type { Calendar } from './calendars'
import type { ResourceType } from './materials'

export type TaskStatus = 'PLANNED' | 'FROZEN' | 'IN_PROGRESS' | 'COMPLETED'

export interface GanttTask {
  id: number
  orderNo: string
  operationIndex: number
  operationName?: string | null
  resourceCode?: string | null
  secondaryResourceCodes?: string[]
  startTime?: string | null
  endTime?: string | null
  /** 契约 C4：setup_seconds=0 时等于 startTime，不允许为空。 */
  setupStartTime?: string
  setupEndTime?: string
  qty?: number | null
  status: TaskStatus
  sequenceInResource?: number | null
  version: number
}

export interface GanttResourceRow {
  resourceCode: string
  resourceName: string
  resourceType: ResourceType
  tasks: GanttTask[]
}

export interface GanttOrderRow {
  orderNo: string
  materialCode: string
  dueDate: string
  tasks: GanttTask[]
}

export interface GanttRangeQuery {
  /** 为空时后端取默认 BASELINE（contract-freeze C5）。 */
  sandboxId?: string
  from: string
  to: string
}

export interface ResourceGanttQuery extends GanttRangeQuery {
  resourceCodes?: string[]
}

export interface OrderGanttQuery extends GanttRangeQuery {
  orderNos?: string[]
}

export interface ResourceGanttData {
  sandboxId: string
  from: string
  to: string
  resources: GanttResourceRow[]
}

export interface OrderGanttData {
  sandboxId: string
  from: string
  to: string
  orders: GanttOrderRow[]
}

export interface TaskAdjustRequest {
  version: number
  resourceCode?: string | null
  secondaryResourceCodes?: string[]
  startTime?: string
  endTime?: string
  frozen?: boolean
  reason?: string
}

export interface PlanAction {
  planId: string
  version: number
  publishedAt: string
  affectedTaskCount: number
}

/**
 * 甘特资源行与工作日历的绑定结果（M2-FE-01 修正）。
 * C4 的 /gantt/resource 不返回 calendarId，需用 GET /resources 的 calendarId + GET /calendars/{id}
 * 在前端补齐；任一环节缺失时必须显式落为 UNBOUND/MISSING/ERROR，不得静默按 24h 回落。
 */
export type GanttCalendarBindingState = 'BOUND' | 'UNBOUND' | 'MISSING' | 'ERROR'

export interface GanttCalendarBinding {
  resourceCode: string
  calendarId: string | null
  state: GanttCalendarBindingState
  calendar: Calendar | null
  /** 面向页面的可读说明：未绑、资源缺失、日历缺失、加载失败原因。 */
  message: string
}

export interface ResourceCalendarResolution {
  bindings: GanttCalendarBinding[]
  /** 去重后的接入缺口（端点/字段级），供页面与报告引用。 */
  issues: string[]
  /** false 表示 GET /resources 整体不可用，所有资源均无法解析日历。 */
  resourceListLoaded: boolean
}
