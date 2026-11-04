/**
 * 资源甘特只读增强层的派生分析（M2-FE-01）。
 *
 * 数据来源分两类，页面必须区分：
 * - 日历事实：资源日历来自 C14（GET /resources 的 calendarId + GET /calendars/{id}），
 *   由 components/gantt/calendar.ts 折算为逐日工作片段与停机窗口；停机层与热力分母只用这份数据。
 * - 前端推导：冲突（同资源/辅资源重叠、工序依赖违例）、可插空档（相邻任务间隔 ≥30 分钟）、
 *   依赖边。这些 C4 不返回，只能由任务条推导，页面标注为「前端推导」。
 *
 * 禁止：用任务间隔推定停机；用固定 24h 作为负载分母；资源未绑日历时静默回落。
 * 未绑 / 资源缺失 / 日历加载失败的行不产生任何热力格，峰值负载为 null（页面显示「—」）。
 *
 * 数据量级：500 任务 × 50 资源仅做一次 O(n log n) 排序 + 线性扫描，单次分析 < 5 ms。
 */
import type { GanttBarModel, GanttDataset } from './index'
import {
  computeCalendarLayers,
  workingOverlapMinutes,
  type GanttCalendarComputation,
  type GanttCalendarDay,
  type GanttCalendarRowState,
  type GanttCalendarSummary,
  type GanttCalendarWindow
} from './calendar'

export type GanttConflictType =
  | 'RESOURCE_OVERLAP'
  | 'SECONDARY_RESOURCE_OVERLAP'
  | 'DEPENDENCY_VIOLATION'

export interface GanttConflict {
  type: GanttConflictType
  /** 冲突双方任务 id，顺序为「先占用 / 后占用」。 */
  taskIds: [number, number]
  resourceCode: string | null
  overlapMinutes: number
  message: string
}

export interface GanttDependencyLink {
  orderNo: string
  fromTaskId: number
  toTaskId: number
  fromRowKey: string
  toRowKey: string
  violated: boolean
  /** 违例分钟数，0 表示满足依赖。 */
  violationMinutes: number
  message: string
}

export interface GanttIdleGap {
  rowKey: string
  startTime: string
  endTime: string
  minutes: number
}

export interface GanttHeatCell {
  rowKey: string
  dayStart: string
  dayEnd: string
  /** 与当日日历工作片段相交的占用分钟数（含换型窗口），热力分子。 */
  occupiedMinutes: number
  /** 与自然日相交的全部占用分钟数（含非工作时段），仅用于 tooltip 说明，不进分母/分子比。 */
  rawOccupiedMinutes: number
  /** 当日日历工作分钟数（C14 班次裁剪），热力分母；> 0 才会生成热力格。 */
  workingMinutes: number
  /** occupiedMinutes / workingMinutes，可能 > 1（资源被重复占用）。 */
  loadRatio: number
}

export interface GanttLayerFlags {
  /** 日历停机窗口（班次休息 / 非排班日 / 非工作例外日），事实来源 C14。 */
  calendar: boolean
  /** 可插空档：相邻任务之间 ≥30 分钟的间隔，前端推导，非停机事实。 */
  idle: boolean
  dependency: boolean
  heat: boolean
  conflict: boolean
  freeze: boolean
}

export interface GanttAnalysisSummary {
  taskCount: number
  resourceCount: number
  conflictCount: number
  conflictTaskCount: number
  frozenTaskCount: number
  dependencyLinkCount: number
  dependencyViolationCount: number
  idleMinutes: number
  /** 峰值负载（按日历工作分钟）；没有任何可比日历行时为 null，页面显示「—」。 */
  peakLoadRatio: number | null
}

export interface GanttAnalysis {
  conflicts: GanttConflict[]
  /** 冲突任务 id 集合，供渲染层加红框。 */
  conflictTaskIds: number[]
  dependencyLinks: GanttDependencyLink[]
  /** 可插空档（前端推导）：相邻任务间隔，仅作可插入候选，不是停机事实。 */
  idleGaps: GanttIdleGap[]
  heatCells: GanttHeatCell[]
  frozenTaskIds: number[]
  /** 日历停机窗口（C14 事实）：班次休息、非排班日、非工作例外日。 */
  calendarWindows: GanttCalendarWindow[]
  calendarDays: GanttCalendarDay[]
  calendarRows: GanttCalendarRowState[]
  calendarSummary: GanttCalendarSummary
  calendarTimezone: string
  /** 日历接入缺口（端点/字段级），页面需显式展示「数据未接入」。 */
  calendarIssues: string[]
  calendarResourceListLoaded: boolean
  summary: GanttAnalysisSummary
}

const MIN_IDLE_MINUTES = 30

interface Interval {
  start: number
  end: number
}

function occupiedInterval(bar: GanttBarModel): Interval {
  const start = Date.parse(bar.startTime)
  const end = Date.parse(bar.endTime)
  const setupStart = bar.setupStartTime ? Date.parse(bar.setupStartTime) : start
  return {
    start: Number.isFinite(setupStart) ? Math.min(setupStart, start) : start,
    end
  }
}

function minutesOf(milliseconds: number): number {
  return Math.round(milliseconds / 60_000)
}

function overlaps(left: Interval, right: Interval): boolean {
  return left.start < right.end && right.start < left.end
}

function overlapMinutes(left: Interval, right: Interval): number {
  return minutesOf(Math.max(0, Math.min(left.end, right.end) - Math.max(left.start, right.start)))
}

function taskLabel(bar: GanttBarModel): string {
  return `#${bar.taskId}（${bar.orderNo} 工序 ${bar.operationIndex}）`
}

function iso(milliseconds: number): string {
  return new Date(milliseconds).toISOString()
}

function loadLevel(ratio: number): 'green' | 'yellow' | 'red' {
  if (ratio >= 0.9) {
    return 'red'
  }
  if (ratio >= 0.7) {
    return 'yellow'
  }
  return 'green'
}

export function heatLevelOf(ratio: number): 'green' | 'yellow' | 'red' {
  return loadLevel(ratio)
}

/**
 * 生成只读增强层分析结果。
 * 纯函数：同一 dataset 每次返回同结构（Set 用数组表达），便于对比与实现。
 */
export function analyzeGanttDataset(dataset: GanttDataset): GanttAnalysis {
  const conflicts: GanttConflict[] = []
  const dependencyLinks: GanttDependencyLink[] = []
  const idleGaps: GanttIdleGap[] = []
  const heatCells: GanttHeatCell[] = []
  const frozenTaskIds: number[] = []
  const conflictTaskIds = new Set<number>()
  let taskCount = 0

  const rangeStart = Date.parse(dataset.range.from)
  const rangeEnd = Date.parse(dataset.range.to)
  const validRange =
    Number.isFinite(rangeStart) && Number.isFinite(rangeEnd) && rangeEnd > rangeStart

  // 日历事实：只接受适配器补齐的 C14 绑定；没有绑定就是未接入，绝不按 24h/Mock 日历兜底。
  const calendar: GanttCalendarComputation = computeCalendarLayers(
    validRange ? (dataset.calendarBindings ?? []) : [],
    dataset.rows.map((row) => row.key),
    validRange ? rangeStart : 0,
    validRange ? rangeEnd : 0
  )
  const daysByRow = new Map<string, GanttCalendarDay[]>()
  calendar.days.forEach((day) => {
    const list = daysByRow.get(day.rowKey) ?? []
    list.push(day)
    daysByRow.set(day.rowKey, list)
  })

  dataset.rows.forEach((row) => {
    const bars = row.bars
    taskCount += bars.length
    bars.forEach((bar) => {
      if (bar.frozen) {
        frozenTaskIds.push(bar.taskId)
      }
    })

    // 1) 同资源冲突：按占用起点排序后线性扫描，保留最大结束时间的占用者作为比较基准。
    const sorted = [...bars].sort((left, right) => {
      const leftInterval = occupiedInterval(left)
      const rightInterval = occupiedInterval(right)
      return leftInterval.start - rightInterval.start || left.taskId - right.taskId
    })
    let anchor: GanttBarModel | null = null
    let anchorEnd = Number.NEGATIVE_INFINITY
    sorted.forEach((bar) => {
      const interval = occupiedInterval(bar)
      if (anchor && overlaps(occupiedInterval(anchor), interval)) {
        const minutes = overlapMinutes(occupiedInterval(anchor), interval)
        conflicts.push({
          type: 'RESOURCE_OVERLAP',
          taskIds: [anchor.taskId, bar.taskId],
          resourceCode: row.key,
          overlapMinutes: minutes,
          message: `${taskLabel(anchor)} 与 ${taskLabel(bar)} 在资源 ${row.key} 上重叠 ${minutes} 分钟`
        })
        conflictTaskIds.add(anchor.taskId)
        conflictTaskIds.add(bar.taskId)
      }
      if (interval.end > anchorEnd) {
        anchor = bar
        anchorEnd = interval.end
      }
    })

    // 2) 可插空档（独立图层，前端推导）：仅统计同一资源相邻任务之间 >= 30 分钟的间隔。
    //    这是「可以再插一个任务」的候选窗口，不是停机事实；停机层只渲染 C14 日历窗口。
    for (let index = 1; index < sorted.length; index += 1) {
      const previous = occupiedInterval(sorted[index - 1])
      const current = occupiedInterval(sorted[index])
      if (current.start <= previous.end) {
        continue
      }
      const minutes = minutesOf(current.start - previous.end)
      if (minutes < MIN_IDLE_MINUTES) {
        continue
      }
      idleGaps.push({
        rowKey: row.key,
        startTime: iso(previous.end),
        endTime: iso(current.start),
        minutes
      })
    }

    // 3) 负载热力：分母为 C14 日历的当日工作分钟，分子为占用与工作片段的交集。
    //    未绑 / 缺失 / 失败的行没有 days，不生成任何热力格（页面显示「—」），不回落到 24h。
    const calendarDays = daysByRow.get(row.key) ?? []
    calendarDays.forEach((day) => {
      if (!day.effective || day.workingMinutes <= 0) {
        return
      }
      const dayStart = Date.parse(day.dayStart)
      const dayEnd = Date.parse(day.dayEnd)
      let occupiedWorking = 0
      let occupiedRaw = 0
      bars.forEach((bar) => {
        const interval = occupiedInterval(bar)
        occupiedRaw += Math.max(
          0,
          Math.min(interval.end, dayEnd) - Math.max(interval.start, dayStart)
        )
        occupiedWorking += workingOverlapMinutes(day.workingSegments, interval.start, interval.end)
      })
      if (occupiedRaw <= 0) {
        return
      }
      heatCells.push({
        rowKey: row.key,
        dayStart: day.dayStart,
        dayEnd: day.dayEnd,
        occupiedMinutes: occupiedWorking,
        rawOccupiedMinutes: minutesOf(occupiedRaw),
        workingMinutes: day.workingMinutes,
        loadRatio: occupiedWorking / day.workingMinutes
      })
    })
  })

  // 4) 辅资源冲突：同一 secondaryResourceCode 上被多个任务同时占用。
  const secondaryCodesOf = builtinSecondaryCodes(dataset)
  secondaryCodesOf.forEach((entries, code) => {
    for (let left = 0; left < entries.length; left += 1) {
      for (let right = left + 1; right < entries.length; right += 1) {
        const leftInterval = occupiedInterval(entries[left].bar)
        const rightInterval = occupiedInterval(entries[right].bar)
        if (entries[left].bar.taskId === entries[right].bar.taskId) {
          continue
        }
        if (!overlaps(leftInterval, rightInterval)) {
          continue
        }
        const minutes = overlapMinutes(leftInterval, rightInterval)
        conflicts.push({
          type: 'SECONDARY_RESOURCE_OVERLAP',
          taskIds: [entries[left].bar.taskId, entries[right].bar.taskId],
          resourceCode: code,
          overlapMinutes: minutes,
          message: `${taskLabel(entries[left].bar)} 与 ${taskLabel(entries[right].bar)} 共用辅资源 ${code} 重叠 ${minutes} 分钟`
        })
        conflictTaskIds.add(entries[left].bar.taskId)
        conflictTaskIds.add(entries[right].bar.taskId)
      }
    }
  })

  // 5) 工序依赖：同订单按 operationIndex 串联；下一道工序的占用起点早于上一道结束即违例。
  const orderGroups = new Map<string, Array<{ rowKey: string; bar: GanttBarModel }>>()
  dataset.rows.forEach((row) => {
    row.bars.forEach((bar) => {
      const group = orderGroups.get(bar.orderNo) ?? []
      group.push({ rowKey: row.key, bar })
      orderGroups.set(bar.orderNo, group)
    })
  })
  orderGroups.forEach((entries) => {
    const sorted = [...entries].sort(
      (left, right) =>
        (left.bar.operationIndex ?? 0) - (right.bar.operationIndex ?? 0) ||
        Date.parse(left.bar.startTime) - Date.parse(right.bar.startTime)
    )
    for (let index = 1; index < sorted.length; index += 1) {
      const from = sorted[index - 1]
      const to = sorted[index]
      if ((from.bar.operationIndex ?? 0) === (to.bar.operationIndex ?? 0)) {
        continue
      }
      const fromInterval = occupiedInterval(from.bar)
      const toInterval = occupiedInterval(to.bar)
      const violation = Math.max(0, minutesOf(fromInterval.end - toInterval.start))
      const violated = toInterval.start < fromInterval.end
      dependencyLinks.push({
        orderNo: from.bar.orderNo,
        fromTaskId: from.bar.taskId,
        toTaskId: to.bar.taskId,
        fromRowKey: from.rowKey,
        toRowKey: to.rowKey,
        violated,
        violationMinutes: violation,
        message: violated
          ? `${to.bar.orderNo} 工序 ${to.bar.operationIndex} 早于工序 ${from.bar.operationIndex} 结束 ${violation} 分钟`
          : `${to.bar.orderNo} 工序 ${from.bar.operationIndex} → ${to.bar.operationIndex}`
      })
      if (violated) {
        conflicts.push({
          type: 'DEPENDENCY_VIOLATION',
          taskIds: [from.bar.taskId, to.bar.taskId],
          resourceCode: to.rowKey,
          overlapMinutes: violation,
          message: dependencyLinks[dependencyLinks.length - 1].message
        })
        conflictTaskIds.add(from.bar.taskId)
        conflictTaskIds.add(to.bar.taskId)
      }
    }
  })

  const peakLoadRatio =
    heatCells.length > 0
      ? heatCells.reduce((max, cell) => Math.max(max, cell.loadRatio), 0)
      : null
  const idleMinutes = idleGaps.reduce((total, gap) => total + gap.minutes, 0)

  return {
    conflicts,
    conflictTaskIds: [...conflictTaskIds],
    dependencyLinks,
    idleGaps,
    heatCells,
    frozenTaskIds,
    calendarWindows: calendar.windows,
    calendarDays: calendar.days,
    calendarRows: calendar.rowStates,
    calendarSummary: calendar.summary,
    calendarTimezone: calendar.summary.timezone,
    calendarIssues: dataset.calendarIssues ?? [],
    calendarResourceListLoaded: dataset.calendarResourceListLoaded ?? true,
    summary: {
      taskCount,
      resourceCount: dataset.rows.length,
      conflictCount: conflicts.length,
      conflictTaskCount: conflictTaskIds.size,
      frozenTaskCount: frozenTaskIds.length,
      dependencyLinkCount: dependencyLinks.length,
      dependencyViolationCount: dependencyLinks.filter((link) => link.violated).length,
      idleMinutes,
      peakLoadRatio
    }
  }
}

/**
 * 辅资源编码分组：既包含任务显式声明的 secondaryResourceCodes，也包含「该资源行本身」被其他任务当作辅资源占用的情况。
 * 同一任务在同一编码下只保留一条，避免自比较。
 */
function builtinSecondaryCodes(
  dataset: GanttDataset
): Map<string, Array<{ rowKey: string; bar: GanttBarModel }>> {
  const codes = new Set<string>()
  dataset.rows.forEach((row) => {
    row.bars.forEach((bar) => {
      ;(bar.secondaryResourceCodes ?? []).forEach((code) => codes.add(code))
    })
  })
  const groups = new Map<string, Array<{ rowKey: string; bar: GanttBarModel }>>()
  const seen = new Set<string>()
  codes.forEach((code) => groups.set(code, []))
  const push = (code: string, rowKey: string, bar: GanttBarModel) => {
    const key = `${code}:${bar.taskId}`
    if (seen.has(key)) {
      return
    }
    seen.add(key)
    groups.get(code)?.push({ rowKey, bar })
  }
  dataset.rows.forEach((row) => {
    row.bars.forEach((bar) => {
      ;(bar.secondaryResourceCodes ?? []).forEach((code) => push(code, row.key, bar))
      if (codes.has(row.key)) {
        push(row.key, row.key, bar)
      }
    })
  })
  return groups
}
