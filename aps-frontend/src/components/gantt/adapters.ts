/**
 * 甘特图适配器实现与工厂（M1-FE-06 + M2-FE-02）。
 * - Mock：默认实现，直接调用 src/mocks/gantt-data.ts 生成固定规模数据。
 * - API：映射 GET /api/v1/gantt/resource（C4）与 /gantt/order（C29）。
 * 订单与资源两视图共用同一套任务时间与 C14 日历解析，不复制口径。
 */
import { ApiError } from '@/api/http'
import { adjustTask, getOrderGantt, getResourceGantt, resolveResourceCalendars } from '@/api/gantt'
import { getEnumLabel } from '@/constants/enums'
import type {
  OrderGanttData,
  ResourceGanttData,
  ResourceCalendarResolution,
  TaskAdjustRequest,
  TaskAdjustResult,
  TaskLockType
} from '@/types/gantt'
import type {
  GanttAdapter,
  GanttBarModel,
  GanttDataset,
  GanttLoadQuery,
  GanttRowModel
} from './index'

import {
  buildMockCalendarBindings,
  generateOrderGanttData,
  generateResourceGanttData,
  type GanttDatasetKey
} from '@/mocks/gantt-data'

export type GanttDataSource = 'mock' | 'api'

export interface CreateGanttAdapterOptions {
  /** Mock 规模档位，默认 48×8（FE-01 验收档）。 */
  dataset?: GanttDatasetKey
  /** 数据源，默认 mock；真实联调改为 api。 */
  source?: GanttDataSource
  /** 视图模式，默认 resource；订单甘特传 order。 */
  mode?: 'resource' | 'order'
}

/** 契约 C4 响应 → 适配器内部模型。 */
export function mapResourceGanttData(data: ResourceGanttData): GanttDataset {
  let unscheduledTaskCount = 0
  const rows: GanttRowModel[] = data.resources.map((resource) => {
    const bars: GanttBarModel[] = []
    resource.tasks.forEach((task) => {
      if (!task.startTime || !task.endTime) {
        unscheduledTaskCount += 1
        return
      }
      const setupStartTime = task.setupStartTime ?? task.startTime
      const setupEndTime = task.setupEndTime ?? task.startTime
      bars.push({
        taskId: task.id,
        orderNo: task.orderNo,
        label: task.operationName ? `${task.operationName} · ${task.orderNo}` : task.orderNo,
        startTime: task.startTime,
        endTime: task.endTime,
        setupStartTime,
        setupEndTime,
        status: task.status,
        frozen: task.status === 'FROZEN',
        version: task.version,
        operationIndex: task.operationIndex,
        operationName: task.operationName ?? null,
        resourceCode: task.resourceCode ?? resource.resourceCode,
        secondaryResourceCodes: task.secondaryResourceCodes ?? [],
        qty: task.qty ?? null,
        sequenceInResource: task.sequenceInResource ?? null,
        lockType: task.lockType ?? (task.status === 'FROZEN' ? 'FROZEN' : 'NONE'),
        late: task.late ?? false
      })
    })
    return {
      key: resource.resourceCode,
      label: resource.resourceName || resource.resourceCode,
      subLabel: getEnumLabel('resourceType', resource.resourceType),
      resourceType: resource.resourceType,
      bars
    }
  })

  return {
    mode: 'resource',
    range: { from: data.from, to: data.to },
    rows,
    unscheduledTaskCount
  }
}

/** 契约 C29 订单响应 → 适配器内部模型；订单行内按 operationIndex 保留工序链。 */
export function mapOrderGanttData(data: OrderGanttData): GanttDataset {
  let unscheduledTaskCount = 0
  const rows: GanttRowModel[] = data.orders.map((order) => {
    const bars: GanttBarModel[] = []
    const resourceCodes = new Set<string>()
    order.tasks.forEach((task) => {
      if (task.resourceCode) {
        resourceCodes.add(task.resourceCode)
      }
      if (!task.startTime || !task.endTime) {
        unscheduledTaskCount += 1
        return
      }
      const setupStartTime = task.setupStartTime ?? task.startTime
      const setupEndTime = task.setupEndTime ?? task.startTime
      bars.push({
        taskId: task.id,
        orderNo: task.orderNo,
        label: task.operationName ?? `工序 ${task.operationIndex}`,
        startTime: task.startTime,
        endTime: task.endTime,
        setupStartTime,
        setupEndTime,
        status: task.status,
        frozen: task.status === 'FROZEN',
        version: task.version,
        operationIndex: task.operationIndex,
        operationName: task.operationName ?? null,
        resourceCode: task.resourceCode ?? null,
        secondaryResourceCodes: task.secondaryResourceCodes ?? [],
        qty: task.qty ?? null,
        sequenceInResource: task.sequenceInResource ?? null,
        lockType: task.lockType ?? (task.status === 'FROZEN' ? 'FROZEN' : 'NONE'),
        late: task.late ?? false
      })
    })
    return {
      key: order.orderNo,
      label: order.orderNo,
      subLabel: `${order.materialCode} · 交期 ${order.dueDate}`,
      bars,
      dueDate: order.dueDate,
      dueDateLine: order.dueDateLine ?? `${order.dueDate}T23:59:59Z`,
      priority: order.priority,
      freezeStatus: order.freezeStatus,
      orderStatus: order.orderStatus,
      late: order.late ?? false,
      lateMinutes: order.lateMinutes ?? null,
      lateTaskCount: order.lateTaskCount ?? 0,
      resourceCodes: [...resourceCodes]
    }
  })

  return {
    mode: 'order',
    range: { from: data.from, to: data.to },
    rows,
    unscheduledTaskCount
  }
}

function filterByRange(dataset: GanttDataset, query: GanttLoadQuery): GanttDataset {
  const from = Date.parse(query.from)
  const to = Date.parse(query.to)
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) {
    return dataset
  }
  const rows = dataset.rows
    .map((row) => ({
      ...row,
      bars: row.bars.filter(
        (bar) => Date.parse(bar.endTime) > from && Date.parse(bar.startTime) < to
      )
    }))
    .filter((row) => row.bars.length > 0)
  return {
    ...dataset,
    range: { from: new Date(from).toISOString(), to: new Date(to).toISOString() },
    rows
  }
}

/** 汇总资源编码后统一解析 C14 日历（订单/资源视图共用）。 */
function withCalendars(
  dataset: GanttDataset,
  resolution: ResourceCalendarResolution,
  issues: string[]
): GanttDataset {
  return {
    ...dataset,
    calendarBindings: resolution.bindings,
    calendarIssues: [...issues, ...resolution.issues],
    calendarResourceListLoaded: resolution.resourceListLoaded
  }
}

/** Mock 适配器：不经过 HTTP，直接生成契约结构数据；日历绑定使用 C14 结构的 Mock 日历。 */
export function createMockGanttAdapter(datasetKey: GanttDatasetKey = '48x8'): GanttAdapter {
  let loaded: GanttDataset | null = null
  return {
    mode: 'resource',
    async load(query) {
      const data = generateResourceGanttData(datasetKey, query.sandboxId)
      const dataset = filterByRange(mapResourceGanttData(data), query)
      const resolution = buildMockCalendarBindings(dataset.rows.map((row) => row.key))
      loaded = withCalendars(dataset, resolution, [])
      return loaded
    },
    async adjust(taskId, payload) {
      if (!loaded) {
        throw new ApiError(40901, '数据尚未加载，请刷新后重试')
      }
      return applyMockAdjust(loaded, taskId, payload)
    }
  }
}

/** API 适配器：走 src/api/gantt.ts，字段与冻结契约 C4 一致；日历经 C14 二次查询补齐。 */
export function createApiGanttAdapter(): GanttAdapter {
  return {
    mode: 'resource',
    async load(query) {
      const data = await getResourceGantt({
        sandboxId: query.sandboxId,
        from: query.from,
        to: query.to,
        resourceCodes: query.rowKeys
      })
      const dataset = mapResourceGanttData(data)
      const resolution = await resolveResourceCalendars(dataset.rows.map((row) => row.key))
      return withCalendars(dataset, resolution, [
        'C4 /gantt/resource 增量返回 calendarId/calendarBound；缺失时回退 GET /resources + GET /calendars/{id}'
      ])
    },
    adjust: (taskId, payload, idempotencyKey) => adjustTask(taskId, payload, idempotencyKey)
  }
}

/** 订单视图 Mock：任务集合与资源档同源，日历按订单涉及资源解析。 */
export function createMockOrderGanttAdapter(datasetKey: GanttDatasetKey = '48x8'): GanttAdapter {
  let loaded: GanttDataset | null = null
  return {
    mode: 'order',
    async load(query) {
      const data = generateOrderGanttData(datasetKey, query.sandboxId)
      const dataset = filterByRange(mapOrderGanttData(data), query)
      const resourceCodes = [...new Set(dataset.rows.flatMap((row) => row.resourceCodes ?? []))]
      const resolution = buildMockCalendarBindings(resourceCodes)
      loaded = withCalendars(dataset, resolution, [])
      return loaded
    },
    async adjust(taskId, payload) {
      if (!loaded) {
        throw new ApiError(40901, '数据尚未加载，请刷新后重试')
      }
      return applyMockAdjust(loaded, taskId, payload)
    }
  }
}

/** 订单视图 API：GET /gantt/order（C29）+ C14 资源日历。 */
export function createApiOrderGanttAdapter(): GanttAdapter {
  return {
    mode: 'order',
    async load(query) {
      const data = await getOrderGantt({
        sandboxId: query.sandboxId,
        from: query.from,
        to: query.to,
        orderNos: query.rowKeys
      })
      const dataset = mapOrderGanttData(data)
      const resourceCodes = [...new Set(dataset.rows.flatMap((row) => row.resourceCodes ?? []))]
      const resolution = await resolveResourceCalendars(resourceCodes)
      return withCalendars(dataset, resolution, [
        'C29 /gantt/order 不含资源日历；按任务 resourceCode 走 GET /resources + GET /calendars/{id}（C14）'
      ])
    },
    adjust: (taskId, payload, idempotencyKey) => adjustTask(taskId, payload, idempotencyKey)
  }
}

/** Mock 调整：按冻结语义模拟错误码，便于无后端写入时演示交互。 */
function applyMockAdjust(
  dataset: GanttDataset,
  taskId: number,
  payload: TaskAdjustRequest
): TaskAdjustResult {
  if (payload.operation === 'SPLIT' || payload.operation === 'MERGE') {
    throw new ApiError(50100, 'SPLIT/MERGE 在 M2 未实现')
  }
  const row = dataset.rows.find((item) => item.bars.some((bar) => bar.taskId === taskId))
  const bar = row?.bars.find((item) => item.taskId === taskId)
  if (!row || !bar) {
    throw new ApiError(40400, '任务不存在或不在当前窗口')
  }
  if (payload.version !== bar.version) {
    throw new ApiError(40901, '任务版本已过期，请刷新后重试')
  }
  if (bar.status === 'IN_PROGRESS' || bar.status === 'COMPLETED') {
    throw new ApiError(40903, '进行中/已完成任务不可调整')
  }
  const relaxing =
    payload.operation === 'MOVE' || payload.operation === 'LOCK' || payload.operation === 'UNFREEZE'
  if (relaxing && row.freezeStatus === 'FROZEN') {
    throw new ApiError(42201, '订单级已冻结，任务级不可放宽')
  }
  const taskLocked = bar.status === 'FROZEN' || bar.lockType === 'FROZEN' || bar.frozen
  if (relaxing && taskLocked) {
    throw new ApiError(42201, '任务已冻结，请先解冻（订单级冻结不可放宽）')
  }

  let nextStatus = bar.status
  let nextLock: TaskLockType = bar.lockType ?? 'NONE'
  let nextStart = bar.startTime
  let nextEnd = bar.endTime
  if (payload.operation === 'MOVE') {
    if (!payload.startTime) {
      throw new ApiError(40000, 'MOVE 必须提供 startTime')
    }
    const start = Date.parse(payload.startTime)
    // 与后端一致：MOVE 按 startTime 平移，保持原加工时长；endTime 仅作显式覆盖。
    const previousDuration = Date.parse(bar.endTime) - Date.parse(bar.startTime)
    const end = payload.endTime ? Date.parse(payload.endTime) : start + previousDuration
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      throw new ApiError(40000, '时间窗必须满足 endTime > startTime（零长度窗会被后端拒绝）')
    }
    nextStart = payload.startTime
    nextEnd = new Date(end).toISOString()
    if (payload.resourceCode && payload.resourceCode !== bar.resourceCode) {
      bar.resourceCode = payload.resourceCode
    }
  }
  if (payload.operation === 'LOCK') {
    const from = payload.lockFrom ? Date.parse(payload.lockFrom) : Number.NaN
    const to = payload.lockTo ? Date.parse(payload.lockTo) : Number.NaN
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) {
      throw new ApiError(
        40000,
        '锁定时间窗必须是闭区间且 lockTo > lockFrom（零长度窗会被 DB CHECK 拒绝）'
      )
    }
    nextLock = 'TIME_WINDOW'
  }
  if (payload.operation === 'FREEZE') {
    nextStatus = 'FROZEN'
    nextLock = 'FROZEN'
  }
  if (payload.operation === 'UNFREEZE') {
    nextStatus = 'PLANNED'
    nextLock = 'NONE'
  }

  const nextVersion = payload.dryRun ? bar.version : bar.version + 1
  const task = {
    id: bar.taskId,
    orderNo: bar.orderNo,
    operationIndex: bar.operationIndex ?? 0,
    operationName: bar.operationName ?? null,
    resourceCode: bar.resourceCode ?? null,
    secondaryResourceCodes: bar.secondaryResourceCodes ?? [],
    setupStartTime: bar.setupStartTime,
    setupEndTime: bar.setupEndTime,
    startTime: nextStart,
    endTime: nextEnd,
    qty: bar.qty ?? null,
    status: nextStatus,
    sequenceInResource: bar.sequenceInResource ?? null,
    version: nextVersion,
    lockType: nextLock,
    late: bar.late ?? false
  }
  if (!payload.dryRun) {
    bar.startTime = nextStart
    bar.endTime = nextEnd
    bar.status = nextStatus
    bar.frozen = nextStatus === 'FROZEN'
    bar.lockType = nextLock
    bar.version = nextVersion
  }
  return {
    adjustmentId: payload.dryRun ? null : `mock-${taskId}-${Date.now()}`,
    applied: true,
    dryRun: Boolean(payload.dryRun),
    task,
    conflicts: []
  }
}

/** 页面只依赖此工厂；真实接口切换只改 source，不改页面。 */
export function createGanttAdapter(options: CreateGanttAdapterOptions = {}): GanttAdapter {
  const mode = options.mode ?? 'resource'
  if (mode === 'order') {
    return options.source === 'api'
      ? createApiOrderGanttAdapter()
      : createMockOrderGanttAdapter(options.dataset)
  }
  return options.source === 'api'
    ? createApiGanttAdapter()
    : createMockGanttAdapter(options.dataset)
}

/** Mock 数据档位对应的时间窗，供页面发起 load 使用。 */
export function getMockQueryRange(): { from: string; to: string } {
  return {
    from: new Date(Date.UTC(2026, 10, 5)).toISOString(),
    to: new Date(Date.UTC(2026, 10, 12)).toISOString()
  }
}

export { GANTT_DATASET_PROFILES, getGanttDatasetProfile } from '@/mocks/gantt-data'
export type { GanttDatasetKey }
