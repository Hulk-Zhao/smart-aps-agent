/**
 * 甘特图适配器实现与工厂（M1-FE-06 PoC）。
 * - Mock：默认实现，直接调用 src/mocks/gantt-data.ts 生成固定规模数据。
 * - API：映射 GET /api/v1/gantt/resource（契约 C4），切换真实接口只需换工厂来源。
 */
import { getResourceGantt, resolveResourceCalendars } from '@/api/gantt'
import { getEnumLabel } from '@/constants/enums'
import type { ResourceGanttData } from '@/types/gantt'
import type {
  GanttAdapter,
  GanttBarModel,
  GanttDataset,
  GanttLoadQuery,
  GanttRowModel
} from './index'

import {
  buildMockCalendarBindings,
  generateResourceGanttData,
  type GanttDatasetKey
} from '@/mocks/gantt-data'

export type GanttDataSource = 'mock' | 'api'

export interface CreateGanttAdapterOptions {
  /** Mock 规模档位，默认 48×8（FE-01 验收档）。 */
  dataset?: GanttDatasetKey
  /** 数据源，默认 mock；真实联调改为 api。 */
  source?: GanttDataSource
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
        sequenceInResource: task.sequenceInResource ?? null
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

/** Mock 适配器：不经过 HTTP，直接生成契约结构数据；日历绑定使用 C14 结构的 Mock 日历。 */
export function createMockGanttAdapter(datasetKey: GanttDatasetKey = '48x8'): GanttAdapter {
  return {
    mode: 'resource',
    async load(query) {
      const data = generateResourceGanttData(datasetKey, query.sandboxId)
      const dataset = filterByRange(mapResourceGanttData(data), query)
      const resolution = buildMockCalendarBindings(dataset.rows.map((row) => row.key))
      return {
        ...dataset,
        calendarBindings: resolution.bindings,
        calendarIssues: resolution.issues,
        calendarResourceListLoaded: resolution.resourceListLoaded
      }
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
      const issues = [
        'C4 /gantt/resource 不返回 calendarId；本页通过 GET /resources（calendarId）+ GET /calendars/{id}（C14）补齐',
        ...resolution.issues
      ]
      return {
        ...dataset,
        calendarBindings: resolution.bindings,
        calendarIssues: issues,
        calendarResourceListLoaded: resolution.resourceListLoaded
      }
    }
  }
}

/** 页面只依赖此工厂；真实接口切换只改 source，不改页面。 */
export function createGanttAdapter(options: CreateGanttAdapterOptions = {}): GanttAdapter {
  if (options.source === 'api') {
    return createApiGanttAdapter()
  }
  return createMockGanttAdapter(options.dataset)
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
