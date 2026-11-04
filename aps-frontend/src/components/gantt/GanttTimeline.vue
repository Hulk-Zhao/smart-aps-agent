<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { getEnumLabel, getEnumTagType } from '@/constants/enums'
import { heatLevelOf } from './analysis'
import type { GanttAnalysis, GanttHeatCell, GanttIdleGap, GanttLayerFlags } from './analysis'
import type {
  GanttCalendarRowState,
  GanttCalendarWindow,
  GanttCalendarWindowKind
} from './calendar'
import type { GanttBarModel, GanttDataset, GanttRowModel, GanttZoom } from './index'
import type { GanttCalendarBinding, TaskStatus } from '@/types/gantt'

const props = withDefaults(
  defineProps<{
    dataset: GanttDataset
    zoom: GanttZoom
    rowHeight?: number
    labelWidth?: number
    height?: number
    /** 纵向虚拟窗口上下缓冲区行数：仅多渲染视口上/下各 N 行（M3-FE-04）。 */
    overscan?: number
    /** 横向虚拟缓冲区像素：仅渲染可视水平窗口左右各 N px 内的任务条。 */
    horizontalBuffer?: number
    /** 只读增强层分析结果；缺省时只渲染基础任务条。 */
    analysis?: GanttAnalysis | null
    /** 图层开关：日历停机（C14）/ 可插空档（推导）/ 依赖 / 热力 / 冲突 / 冻结。 */
    layers?: GanttLayerFlags
    /** 订单视图选中任务（M2-FE-03 调整面板使用），为空表示未选。 */
    selectedTaskId?: number | null
  }>(),
  {
    rowHeight: 34,
    labelWidth: 232,
    height: 460,
    overscan: 6,
    horizontalBuffer: 240,
    analysis: null,
    selectedTaskId: null,
    layers: () => ({
      calendar: true,
      idle: true,
      dependency: true,
      heat: false,
      conflict: true,
      freeze: true
    })
  }
)
const emit = defineEmits<{ (event: 'select', taskId: number): void }>()

const scrollRef = ref<HTMLElement | null>(null)
const headsRef = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const scrollLeft = ref(0)
const viewportWidth = ref(0)
const hovered = ref<{ bar: GanttBarModel; x: number; y: number } | null>(null)

const ZOOM_PX_PER_DAY: Record<GanttZoom, number> = {
  hour: 960,
  day: 168,
  week: 56,
  month: 24
}
const ZOOM_STEP_MS: Record<GanttZoom, number> = {
  hour: 2 * 3_600_000,
  day: 86_400_000,
  week: 86_400_000,
  month: 7 * 86_400_000
}

const rangeStart = computed(() => Date.parse(props.dataset.range.from))
const rangeEnd = computed(() => Date.parse(props.dataset.range.to))
const totalMs = computed(() => Math.max(rangeEnd.value - rangeStart.value, 3_600_000))
const pxPerMs = computed(() => ZOOM_PX_PER_DAY[props.zoom] / 86_400_000)
const timelineWidth = computed(() => Math.max(Math.round(totalMs.value * pxPerMs.value), 320))
const rows = computed(() => props.dataset.rows)
const totalHeight = computed(() => rows.value.length * props.rowHeight)
/**
 * 纵向虚拟窗口：视口高 460px ≈ 13 行（rowHeight=34px），上下各多渲染 overscan=6 行。
 * 行节点绝对定位在 totalHeight 画布上，滚动只替换窗口内 25 行左右的 DOM，行数（5000）不进入 DOM 数量。
 */
const startIndex = computed(() =>
  Math.max(0, Math.floor(scrollTop.value / props.rowHeight) - props.overscan)
)
const endIndex = computed(() =>
  Math.min(
    rows.value.length,
    Math.ceil((scrollTop.value + props.height) / props.rowHeight) + props.overscan
  )
)
/** 横向虚拟窗口：只渲染 scrollLeft ± (clientWidth + horizontalBuffer) 内的条，避免 5000 条同时挂载。 */
const windowLeft = computed(() => scrollLeft.value - props.horizontalBuffer)
const windowRight = computed(
  () => scrollLeft.value + (viewportWidth.value || timelineWidth.value) + props.horizontalBuffer
)
function inWindow(left: number, width: number): boolean {
  return left + width >= windowLeft.value && left <= windowRight.value
}

const heatByRow = computed(() => {
  const map = new Map<string, GanttHeatCell[]>()
  if (!props.layers.heat || !props.analysis) {
    return map
  }
  props.analysis.heatCells.forEach((cell) => {
    const list = map.get(cell.rowKey) ?? []
    list.push(cell)
    map.set(cell.rowKey, list)
  })
  return map
})

const idleByRow = computed(() => {
  const map = new Map<string, GanttIdleGap[]>()
  if (!props.layers.idle || !props.analysis) {
    return map
  }
  props.analysis.idleGaps.forEach((gap) => {
    const list = map.get(gap.rowKey) ?? []
    list.push(gap)
    map.set(gap.rowKey, list)
  })
  return map
})

/** 日历停机窗口（C14 事实）：班次休息 / 非排班日 / 非工作例外日。 */
const calendarByRow = computed(() => {
  const map = new Map<string, GanttCalendarWindow[]>()
  if (!props.layers.calendar || !props.analysis) {
    return map
  }
  props.analysis.calendarWindows.forEach((window) => {
    const list = map.get(window.rowKey) ?? []
    list.push(window)
    map.set(window.rowKey, list)
  })
  return map
})

/** 行头日历状态：始终展示（与图层开关无关），便于识别未绑/缺失/失败。 */
const calendarRowByKey = computed(() => {
  const map = new Map<string, GanttCalendarRowState>()
  props.analysis?.calendarRows.forEach((row) => map.set(row.rowKey, row))
  return map
})

/** 冲突任务 → 可读原因列表；图层关闭时不加红框，但面板仍可展示原始分析。 */
const conflictReasons = computed(() => {
  const map = new Map<number, string[]>()
  if (!props.layers.conflict || !props.analysis) {
    return map
  }
  props.analysis.conflicts.forEach((conflict) => {
    const message = `[${conflictLabel(conflict.type)}] ${conflict.message}`
    conflict.taskIds.forEach((taskId) => {
      const list = map.get(taskId) ?? []
      if (!list.includes(message)) {
        list.push(message)
      }
      map.set(taskId, list)
    })
  })
  return map
})

const conflictCountByRow = computed(() => {
  const map = new Map<string, number>()
  const rowKeysByTask = new Map<number, string[]>()
  rows.value.forEach((row) => {
    row.bars.forEach((bar) => {
      const list = rowKeysByTask.get(bar.taskId) ?? []
      list.push(row.key)
      rowKeysByTask.set(bar.taskId, list)
    })
  })
  const seen = new Set<string>()
  props.analysis?.conflicts.forEach((conflict, index) => {
    conflict.taskIds.forEach((taskId) => {
      ;(rowKeysByTask.get(taskId) ?? []).forEach((rowKey) => {
        const key = `${rowKey}:${index}`
        if (seen.has(key)) {
          return
        }
        seen.add(key)
        map.set(rowKey, (map.get(rowKey) ?? 0) + 1)
      })
    })
  })
  return map
})

/** 订单视图：C14 资源日历绑定状态按资源编码索引；资源视图的行 key 本身即资源编码。 */
const calendarBindingByCode = computed(() => {
  const map = new Map<string, GanttCalendarBinding>()
  props.dataset.calendarBindings?.forEach((binding) => map.set(binding.resourceCode, binding))
  return map
})

/** 订单视图：任务落在其资源日历非工作时段的校验结果（由「日历停机」图层开关控制显隐）。 */
const calendarCheckByTask = computed(() => {
  const map = new Map<number, number>()
  if (!props.layers.calendar) {
    return map
  }
  props.analysis?.calendarChecks.forEach((check) => map.set(check.taskId, check.offCalendarMinutes))
  return map
})

interface RenderBox {
  key: string
  left: number
  width: number
  label: string
  level?: 'green' | 'yellow' | 'red'
  kind?: GanttCalendarWindowKind
}

interface RenderBar {
  bar: GanttBarModel
  left: number
  width: number
}
interface RenderSetup {
  taskId: number
  left: number
  width: number
}
interface RenderRow {
  row: GanttDataset['rows'][number]
  top: number
  bars: RenderBar[]
  setups: RenderSetup[]
  heat: RenderBox[]
  idle: RenderBox[]
  calendar: RenderBox[]
  /** 订单交期线在画布上的 x 坐标；资源视图或交期不在窗口内时为 null。 */
  dueLeft: number | null
  calendarShort: string
  calendarState: GanttCalendarRowState['state'] | null
}

function boxOf(
  startMs: number,
  endMs: number,
  label: string,
  keyPrefix: string,
  index: number
): RenderBox | null {
  const from = rangeStart.value
  const left = Math.max((startMs - from) * pxPerMs.value, 0)
  const right = Math.min((endMs - from) * pxPerMs.value, timelineWidth.value)
  if (right - left <= 0) {
    return null
  }
  return { key: `${keyPrefix}-${index}`, left, width: right - left, label }
}

function formatDay(value: string): string {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function formatWindow(value: string): string {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(
    2,
    '0'
  )} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function heatLabel(cell: GanttHeatCell): string {
  const occupied = (Math.round(cell.occupiedMinutes / 6) / 10).toFixed(1)
  const working = (Math.round(cell.workingMinutes / 6) / 10).toFixed(1)
  const rawNote =
    cell.rawOccupiedMinutes > cell.occupiedMinutes
      ? `；含非工作时段占用 ${(Math.round(cell.rawOccupiedMinutes / 6) / 10).toFixed(1)}h（不计入分子）`
      : ''
  return `${formatDay(cell.dayStart)} 负载 ${Math.round(cell.loadRatio * 100)}%（占用 ${occupied}h / 日历工作 ${working}h）${rawNote}`
}

function calendarWindowLabel(window: GanttCalendarWindow): string {
  return `日历停机[${calendarKindLabel(window.kind)}] · ${window.reason} · ${formatWindow(
    window.startTime
  )} → ${formatWindow(window.endTime)}`
}

function calendarKindLabel(kind: GanttCalendarWindowKind): string {
  if (kind === 'HOLIDAY') {
    return '非工作例外日'
  }
  if (kind === 'NON_WORKDAY') {
    return '非排班日'
  }
  return '班次休息'
}

function calendarShortOf(state: GanttCalendarRowState | undefined): string {
  if (!state) {
    return '日历未接入'
  }
  if (state.state === 'BOUND') {
    return state.calendarName ?? '已绑日历'
  }
  if (state.state === 'UNBOUND') {
    return '未绑日历·热力不可比'
  }
  if (state.state === 'MISSING') {
    return '资源/日历缺失·热力不可比'
  }
  return '日历加载失败·热力不可比'
}

/** 订单行头日历摘要：按该订单涉及的资源统计 C14 绑定情况（订单行本身没有单一日历）。 */
function orderCalendarText(row: GanttRowModel): { text: string; cls: string } {
  const codes = row.resourceCodes ?? []
  if (codes.length === 0) {
    return { text: '无资源', cls: 'is-none' }
  }
  const bound = codes.filter(
    (code) => calendarBindingByCode.value.get(code)?.state === 'BOUND'
  ).length
  if (bound === codes.length) {
    return { text: `资源 ${codes.length} · 日历已绑`, cls: 'is-bound' }
  }
  return {
    text: `资源 ${codes.length} · 已绑 ${bound}·热力不可比`,
    cls: bound === 0 ? 'is-missing' : 'is-unbound'
  }
}

/**
 * 锁优先级（已冻结语义）：FROZEN > lockType > frozen:boolean。
 * 订单级 freeze_status=FROZEN 与任务级 FROZEN 是交集关系：任一为 FROZEN 即整体不可放宽。
 */
function lockKindOf(
  row: GanttRowModel,
  bar: GanttBarModel
): 'FROZEN' | 'TIME_WINDOW' | 'RESOURCE' | 'NONE' {
  if (
    row.freezeStatus === 'FROZEN' ||
    bar.status === 'FROZEN' ||
    bar.frozen ||
    bar.lockType === 'FROZEN'
  ) {
    return 'FROZEN'
  }
  if (bar.lockType === 'TIME_WINDOW' || bar.lockType === 'RESOURCE') {
    return bar.lockType
  }
  return 'NONE'
}

function lockLabel(kind: 'FROZEN' | 'TIME_WINDOW' | 'RESOURCE' | 'NONE'): string {
  if (kind === 'FROZEN') {
    return '冻'
  }
  if (kind === 'TIME_WINDOW') {
    return '窗'
  }
  if (kind === 'RESOURCE') {
    return '资'
  }
  return ''
}

function lateText(row: GanttRowModel): string {
  if (!row.late) {
    return ''
  }
  return row.lateMinutes == null ? '延期' : `延期 ${row.lateMinutes} 分`
}

function priorityLabel(priority?: string): string {
  if (priority === 'URGENT') {
    return '紧急'
  }
  if (priority === 'HIGH') {
    return '高'
  }
  if (priority === 'LOW') {
    return '低'
  }
  return '普通'
}

const renderRows = computed<RenderRow[]>(() => {
  const from = rangeStart.value
  const to = rangeEnd.value
  const px = pxPerMs.value
  return rows.value.slice(startIndex.value, endIndex.value).map((row, offset) => {
    const index = startIndex.value + offset
    const bars: RenderBar[] = []
    const setups: RenderSetup[] = []
    row.bars.forEach((bar) => {
      const start = Date.parse(bar.startTime)
      const end = Date.parse(bar.endTime)
      if (!Number.isFinite(start) || !Number.isFinite(end) || end <= from || start >= to) {
        return
      }
      const left = Math.max((start - from) * px, 0)
      const width = Math.max((end - start) * px, 2)
      const clipped = Math.min(width, timelineWidth.value - left)
      if (!inWindow(left, clipped)) {
        return
      }
      bars.push({ bar, left, width: clipped })
      if (bar.setupStartTime && bar.setupEndTime) {
        const setupStart = Date.parse(bar.setupStartTime)
        const setupEnd = Date.parse(bar.setupEndTime)
        if (Number.isFinite(setupStart) && Number.isFinite(setupEnd) && setupEnd > setupStart) {
          const setupLeft = Math.max((setupStart - from) * px, 0)
          const setupWidth = Math.max((setupEnd - setupStart) * px, 1)
          const setupClipped = Math.min(setupWidth, timelineWidth.value - setupLeft)
          if (setupLeft < timelineWidth.value && inWindow(setupLeft, setupClipped)) {
            setups.push({
              taskId: bar.taskId,
              left: setupLeft,
              width: setupClipped
            })
          }
        }
      }
    })

    const heat: RenderBox[] = []
    ;(heatByRow.value.get(row.key) ?? []).forEach((cell, cellIndex) => {
      const box = boxOf(
        Date.parse(cell.dayStart),
        Date.parse(cell.dayEnd),
        heatLabel(cell),
        `heat-${row.key}`,
        cellIndex
      )
      if (box) {
        heat.push({ ...box, level: heatLevelOf(cell.loadRatio) })
      }
    })

    const idle: RenderBox[] = []
    ;(idleByRow.value.get(row.key) ?? []).forEach((gap, gapIndex) => {
      const box = boxOf(
        Date.parse(gap.startTime),
        Date.parse(gap.endTime),
        `可插空档 ${gap.minutes} 分钟（相邻任务间隔，前端推导，非停机）`,
        `idle-${row.key}`,
        gapIndex
      )
      if (box) {
        idle.push(box)
      }
    })

    const calendar: RenderBox[] = []
    ;(calendarByRow.value.get(row.key) ?? []).forEach((window, windowIndex) => {
      const box = boxOf(
        Date.parse(window.startTime),
        Date.parse(window.endTime),
        calendarWindowLabel(window),
        `cal-${row.key}`,
        windowIndex
      )
      if (box) {
        calendar.push({ ...box, kind: window.kind })
      }
    })

    return {
      row,
      top: index * props.rowHeight,
      bars,
      setups,
      heat,
      idle,
      calendar,
      dueLeft: dueLineLeft(row, from, to),
      calendarShort: calendarShortOf(calendarRowByKey.value.get(row.key)),
      calendarState: calendarRowByKey.value.get(row.key)?.state ?? null
    }
  })
})

/** 订单交期线：优先用 C29 dueDateLine，缺失时按 dueDate 当日 UTC 23:59:59 推导（不新增字段）。 */
function dueLineLeft(row: GanttRowModel, from: number, to: number): number | null {
  if (props.dataset.mode !== 'order') {
    return null
  }
  const raw = row.dueDateLine ?? (row.dueDate ? `${row.dueDate}T23:59:59Z` : null)
  if (!raw) {
    return null
  }
  const time = Date.parse(raw)
  if (!Number.isFinite(time) || time <= from || time >= to) {
    return null
  }
  return (time - from) * pxPerMs.value
}

const barMap = computed(() => {
  const map = new Map<number, GanttBarModel>()
  rows.value.forEach((row) => row.bars.forEach((bar) => map.set(bar.taskId, bar)))
  return map
})

const barBoxes = computed(() => {
  const map = new Map<number, { left: number; width: number; top: number }>()
  renderRows.value.forEach((item) => {
    item.bars.forEach((entry) => {
      map.set(entry.bar.taskId, { left: entry.left, width: entry.width, top: item.top })
    })
  })
  return map
})

interface LinkArrow {
  id: string
  path: string
  violated: boolean
}

/** 依赖箭头只在两端都处于当前虚拟滚动窗口内时绘制，避免为不可见行计算坐标。 */
const linkArrows = computed<LinkArrow[]>(() => {
  if (!props.layers.dependency || !props.analysis) {
    return []
  }
  const boxes = barBoxes.value
  const arrows: LinkArrow[] = []
  props.analysis.dependencyLinks.forEach((link) => {
    const from = boxes.get(link.fromTaskId)
    const to = boxes.get(link.toTaskId)
    if (!from || !to) {
      return
    }
    const x1 = Math.min(from.left + from.width + 3, timelineWidth.value - 1)
    const y1 = from.top + props.rowHeight / 2
    const x2 = Math.max(to.left - 3, 2)
    const y2 = to.top + props.rowHeight / 2
    arrows.push({
      id: `${link.fromTaskId}-${link.toTaskId}`,
      path: `M ${x1} ${y1} C ${x1 + 24} ${y1}, ${x2 - 24} ${y2}, ${x2} ${y2}`,
      violated: link.violated
    })
  })
  return arrows
})

interface Tick {
  time: number
  left: number
  label: string
  major: boolean
}

const ticks = computed<Tick[]>(() => {
  const step = ZOOM_STEP_MS[props.zoom]
  const items: Tick[] = []
  const first = Math.ceil(rangeStart.value / step) * step
  for (let time = first; time <= rangeEnd.value; time += step) {
    const date = new Date(time)
    const left = (time - rangeStart.value) * pxPerMs.value
    const label =
      props.zoom === 'hour'
        ? `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
        : `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const major =
      props.zoom === 'hour'
        ? date.getHours() % 12 === 0
        : props.zoom === 'week'
          ? date.getDay() === 1
          : true
    items.push({ time, left, label, major })
  }
  return items
})

const gridStyle = computed(() => ({
  backgroundSize: `${ZOOM_STEP_MS[props.zoom] * pxPerMs.value}px 100%`
}))

function statusClass(status: TaskStatus): string {
  return `tag-${getEnumTagType('taskStatus', status)}`
}

function conflictLabel(type: string): string {
  if (type === 'DEPENDENCY_VIOLATION') {
    return '依赖违例'
  }
  if (type === 'SECONDARY_RESOURCE_OVERLAP') {
    return '辅资源重叠'
  }
  return '资源重叠'
}

function conflictsOf(taskId: number): string[] {
  return conflictReasons.value.get(taskId) ?? []
}

function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function onScroll(event: Event): void {
  const target = event.currentTarget as HTMLElement
  scrollTop.value = target.scrollTop
  scrollLeft.value = target.scrollLeft
  if (headsRef.value) {
    headsRef.value.scrollTop = target.scrollTop
  }
  hovered.value = null
}

function resolveBar(target: EventTarget | null): GanttBarModel | null {
  const element =
    target instanceof HTMLElement ? target.closest<HTMLElement>('[data-task-id]') : null
  if (!element) {
    return null
  }
  return barMap.value.get(Number(element.dataset.taskId)) ?? null
}

function onOver(event: MouseEvent): void {
  const bar = resolveBar(event.target)
  if (!bar) {
    return
  }
  hovered.value = { bar, x: event.clientX + 14, y: event.clientY + 14 }
}

function onMove(event: MouseEvent): void {
  if (!hovered.value) {
    return
  }
  hovered.value = { bar: hovered.value.bar, x: event.clientX + 14, y: event.clientY + 14 }
}

function onLeave(): void {
  hovered.value = null
}

/** 点击任务条：订单视图交给页面打开调整面板（M2-FE-03）；只读视图可忽略 select 事件。 */
function onClick(event: MouseEvent): void {
  const bar = resolveBar(event.target)
  if (bar) {
    emit('select', bar.taskId)
  }
}

function isSelected(taskId: number): boolean {
  return props.selectedTaskId === taskId
}

/** 键盘选中：Enter / Space 与鼠标点击等价（可访问性，M2-FE-03 调整面板入口）。 */
function selectBar(bar: GanttBarModel): void {
  emit('select', bar.taskId)
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  const element = scrollRef.value
  if (!element) {
    return
  }
  scrollTop.value = element.scrollTop
  scrollLeft.value = element.scrollLeft
  viewportWidth.value = element.clientWidth
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      viewportWidth.value = element.clientWidth
    })
    resizeObserver.observe(element)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <div class="gantt-timeline" :style="{ height: `${props.height}px` }">
    <div class="gantt-heads" ref="headsRef" :style="{ width: `${props.labelWidth}px` }">
      <div class="gantt-heads__corner">
        {{ props.dataset.mode === 'order' ? '订单泳道' : '资源泳道' }}
      </div>
      <div class="gantt-heads__canvas" :style="{ height: `${totalHeight}px` }">
        <div
          v-for="item in renderRows"
          :key="item.row.key"
          class="gantt-heads__row"
          :style="{ top: `${item.top}px`, height: `${props.rowHeight}px` }"
        >
          <template v-if="props.dataset.mode === 'order'">
            <span class="gantt-heads__name" :title="item.row.label">
              {{ item.row.label }}
              <em v-if="item.row.late" class="gantt-heads__late">{{ lateText(item.row) }}</em>
            </span>
            <span class="gantt-heads__meta" :title="`${item.row.subLabel ?? ''}`">
              {{ item.row.subLabel ?? '—' }} · 优先级 {{ priorityLabel(item.row.priority) }}
              <span v-if="item.row.freezeStatus === 'FROZEN'" class="gantt-heads__freeze">
                · 订单冻结</span
              >
              · {{ item.row.bars.length }} 任务<span
                v-if="conflictCountByRow.get(item.row.key)"
                class="gantt-heads__conflict"
              >
                · 冲突 {{ conflictCountByRow.get(item.row.key) }}</span
              >
              ·
              <span class="gantt-heads__calendar" :class="orderCalendarText(item.row).cls">{{
                orderCalendarText(item.row).text
              }}</span>
            </span>
          </template>
          <template v-else>
            <span class="gantt-heads__name" :title="item.row.label">{{ item.row.label }}</span>
            <span class="gantt-heads__meta"
              >{{ item.row.key }} · {{ item.row.subLabel ?? '—' }} ·
              {{ item.row.bars.length }} 条<span
                v-if="conflictCountByRow.get(item.row.key)"
                class="gantt-heads__conflict"
              >
                · 冲突 {{ conflictCountByRow.get(item.row.key) }}</span
              >
              ·
              <span
                class="gantt-heads__calendar"
                :class="item.calendarState ? `is-${item.calendarState.toLowerCase()}` : 'is-none'"
                >{{ item.calendarShort }}</span
              ></span
            >
          </template>
        </div>
      </div>
    </div>

    <div class="gantt-scroll" ref="scrollRef" @scroll="onScroll">
      <div class="gantt-canvas" :style="{ width: `${timelineWidth}px` }">
        <div class="gantt-axis" :style="{ height: '44px' }">
          <div
            v-for="tick in ticks"
            :key="tick.time"
            class="gantt-axis__tick"
            :class="{ 'is-major': tick.major }"
            :style="{ left: `${tick.left}px` }"
          >
            <span class="gantt-axis__label">{{ tick.label }}</span>
            <i class="gantt-axis__line" />
          </div>
        </div>
        <div
          class="gantt-body"
          :style="{ height: `${totalHeight}px` }"
          @mouseover="onOver"
          @mousemove="onMove"
          @mouseleave="onLeave"
          @click="onClick"
        >
          <div class="gantt-grid" :style="gridStyle" />
          <div
            v-for="item in renderRows"
            :key="item.row.key"
            class="gantt-row"
            :style="{ top: `${item.top}px`, height: `${props.rowHeight}px` }"
          >
            <div
              v-if="item.dueLeft !== null"
              class="gantt-due-line"
              :style="{ left: `${item.dueLeft}px` }"
              :title="`订单交期线 ${item.row.dueDateLine ?? item.row.dueDate ?? ''}`"
            />
            <div
              v-for="window in item.calendar"
              :key="window.key"
              class="gantt-calendar-off"
              :class="window.kind ? `off-${window.kind.toLowerCase()}` : undefined"
              :style="{ left: `${window.left}px`, width: `${window.width}px` }"
              :title="window.label"
            />
            <div
              v-for="gap in item.idle"
              :key="gap.key"
              class="gantt-idle"
              :style="{ left: `${gap.left}px`, width: `${gap.width}px` }"
              :title="gap.label"
            />
            <div
              v-for="cell in item.heat"
              :key="cell.key"
              class="gantt-heat"
              :class="cell.level ? `heat-${cell.level}` : undefined"
              :style="{ left: `${cell.left}px`, width: `${cell.width}px` }"
              :title="cell.label"
            />
            <div
              v-for="setup in item.setups"
              :key="`setup-${setup.taskId}`"
              class="gantt-setup"
              :style="{ left: `${setup.left}px`, width: `${setup.width}px` }"
              :data-task-id="setup.taskId"
            />
            <div
              v-for="entry in item.bars"
              :key="entry.bar.taskId"
              class="gantt-bar"
              :class="[
                statusClass(entry.bar.status),
                {
                  'is-frozen': lockKindOf(item.row, entry.bar) === 'FROZEN',
                  'is-window-locked': entry.bar.lockType === 'TIME_WINDOW',
                  'is-resource-locked': entry.bar.lockType === 'RESOURCE',
                  'is-conflict': conflictsOf(entry.bar.taskId).length > 0,
                  'is-late': entry.bar.late === true,
                  'is-off-calendar': calendarCheckByTask.has(entry.bar.taskId),
                  'is-selected': isSelected(entry.bar.taskId)
                }
              ]"
              :style="{ left: `${entry.left}px`, width: `${entry.width}px` }"
              :data-task-id="entry.bar.taskId"
              tabindex="0"
              role="button"
              :aria-label="`${entry.bar.orderNo} 工序 ${entry.bar.operationIndex} ${entry.bar.label}，状态 ${entry.bar.status}${isSelected(entry.bar.taskId) ? '，已选中' : ''}`"
              :aria-pressed="isSelected(entry.bar.taskId)"
              @keydown.enter.prevent="selectBar(entry.bar)"
              @keydown.space.prevent="selectBar(entry.bar)"
              :title="
                lockKindOf(item.row, entry.bar) === 'FROZEN'
                  ? '已冻结：只读，不可拖拽'
                  : entry.bar.late
                    ? '该任务结束时间超过订单交期线'
                    : undefined
              "
            >
              <span v-if="lockLabel(lockKindOf(item.row, entry.bar))" class="gantt-bar__lock">{{
                lockLabel(lockKindOf(item.row, entry.bar))
              }}</span>
              <span v-if="calendarCheckByTask.has(entry.bar.taskId)" class="gantt-bar__off-calendar"
                >非工时</span
              >
              <span class="gantt-bar__label">{{ entry.bar.label }}</span>
            </div>
          </div>

          <svg
            v-if="linkArrows.length > 0"
            class="gantt-links"
            :width="timelineWidth"
            :height="totalHeight"
          >
            <defs>
              <marker
                id="gantt-arrow-normal"
                markerWidth="6"
                markerHeight="6"
                refX="5"
                refY="3"
                orient="auto"
              >
                <path d="M0,0 L6,3 L0,6 Z" style="fill: var(--primary)" />
              </marker>
              <marker
                id="gantt-arrow-violated"
                markerWidth="6"
                markerHeight="6"
                refX="5"
                refY="3"
                orient="auto"
              >
                <path d="M0,0 L6,3 L0,6 Z" style="fill: var(--danger)" />
              </marker>
            </defs>
            <path
              v-for="arrow in linkArrows"
              :key="arrow.id"
              :d="arrow.path"
              class="gantt-link"
              :class="{ 'is-violated': arrow.violated }"
              :marker-end="
                arrow.violated ? 'url(#gantt-arrow-violated)' : 'url(#gantt-arrow-normal)'
              "
            />
          </svg>
        </div>
      </div>
    </div>

    <div
      v-if="hovered"
      class="gantt-tooltip"
      :style="{ left: `${hovered.x}px`, top: `${hovered.y}px` }"
    >
      <div class="gantt-tooltip__title">
        {{ hovered.bar.orderNo }} · 工序 {{ hovered.bar.operationIndex }}
      </div>
      <div>资源：{{ hovered.bar.resourceCode ?? '—' }}</div>
      <div>
        加工：{{ formatTime(hovered.bar.startTime) }} → {{ formatTime(hovered.bar.endTime) }}
      </div>
      <div>
        换型：{{ formatTime(hovered.bar.setupStartTime) }} →
        {{ formatTime(hovered.bar.setupEndTime) }}
      </div>
      <div>
        数量：{{ hovered.bar.qty ?? '—' }} · 资源内序号：{{ hovered.bar.sequenceInResource ?? '—' }}
      </div>
      <div>
        状态：{{ getEnumLabel('taskStatus', hovered.bar.status) }} · 版本 {{ hovered.bar.version }}
      </div>
      <div v-if="hovered.bar.lockType && hovered.bar.lockType !== 'NONE'">
        锁类型：{{ hovered.bar.lockType }}（优先级 FROZEN &gt; lockType &gt; frozen）
      </div>
      <div v-if="hovered.bar.late">交期：已超期</div>
      <div v-if="calendarCheckByTask.has(hovered.bar.taskId)" class="gantt-tooltip__conflict">
        日历校验：该任务有
        {{ calendarCheckByTask.get(hovered.bar.taskId) }} 分钟落在其资源非工作时段
      </div>
      <div v-if="hovered.bar.secondaryResourceCodes?.length">
        辅资源：{{ hovered.bar.secondaryResourceCodes.join('、') }}
      </div>
      <div
        v-for="reason in conflictsOf(hovered.bar.taskId)"
        :key="reason"
        class="gantt-tooltip__conflict"
      >
        冲突：{{ reason }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.gantt-timeline {
  display: flex;
  border: var(--border-width) solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--card);
  position: relative;
}

.gantt-heads {
  flex: 0 0 auto;
  overflow: hidden;
  border-right: var(--border-width) solid var(--border);
  background: var(--bg-soft);
}

.gantt-heads__corner {
  position: sticky;
  top: 0;
  z-index: 3;
  height: 44px;
  display: flex;
  align-items: center;
  padding: 0 var(--space-3);
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-2);
  background: var(--bg-soft);
  border-bottom: var(--border-width) solid var(--border);
}

.gantt-heads__canvas {
  position: relative;
}

.gantt-heads__row {
  position: absolute;
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0 var(--space-3);
  border-bottom: var(--border-width) solid var(--border);
  overflow: hidden;
}

.gantt-heads__name {
  font-size: var(--font-sm);
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gantt-heads__meta {
  font-size: var(--font-xs);
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gantt-heads__conflict {
  color: var(--danger);
  font-weight: 600;
}

.gantt-heads__calendar {
  font-weight: 600;
}

.gantt-heads__calendar.is-bound {
  color: var(--ok);
}

.gantt-heads__calendar.is-unbound {
  color: var(--warn);
}

.gantt-heads__calendar.is-missing,
.gantt-heads__calendar.is-error {
  color: var(--danger);
}

.gantt-heads__calendar.is-none {
  color: var(--text-3);
}

.gantt-heads__late {
  color: var(--danger);
  font-style: normal;
  font-weight: 600;
  margin-left: 2px;
}

.gantt-heads__freeze {
  color: var(--text-3);
  font-weight: 600;
}

/* 订单交期线：C29 dueDateLine，虚线竖线，仅在订单视图出现。 */
.gantt-due-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  border-left: 1px dashed var(--warn);
  pointer-events: none;
  z-index: 1;
}

.gantt-scroll {
  flex: 1 1 auto;
  overflow: auto;
}

.gantt-canvas {
  min-width: 100%;
}

.gantt-axis {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--bg-soft);
  border-bottom: var(--border-width) solid var(--border);
}

.gantt-axis__tick {
  position: absolute;
  top: 0;
  height: 100%;
  border-left: var(--border-width) solid var(--border);
}

.gantt-axis__tick.is-major {
  border-left-color: var(--border-strong);
}

.gantt-axis__label {
  position: absolute;
  top: 8px;
  left: 4px;
  font-size: var(--font-xs);
  color: var(--text-2);
  white-space: nowrap;
}

.gantt-axis__tick.is-major .gantt-axis__label {
  color: var(--text);
  font-weight: 600;
}

.gantt-axis__line {
  display: none;
}

.gantt-body {
  position: relative;
}

.gantt-grid {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(to right, var(--border) 1px, transparent 1px);
  pointer-events: none;
}

.gantt-row {
  position: absolute;
  left: 0;
  right: 0;
  border-bottom: var(--border-width) solid var(--border);
}

.gantt-row:nth-child(even) {
  background: color-mix(in srgb, var(--card) 60%, transparent);
}

.gantt-heat {
  position: absolute;
  top: 0;
  bottom: 0;
  pointer-events: none;
}

.gantt-heat.heat-green {
  background: color-mix(in srgb, var(--ok) 16%, transparent);
}

.gantt-heat.heat-yellow {
  background: color-mix(in srgb, var(--warn) 24%, transparent);
}

.gantt-heat.heat-red {
  background: color-mix(in srgb, var(--danger) 28%, transparent);
}

/* 日历停机层（C14 事实）：班次休息 / 非排班日 / 非工作例外日，均来自资源工作日历。 */
.gantt-calendar-off {
  position: absolute;
  top: 0;
  bottom: 0;
  pointer-events: none;
}

.gantt-calendar-off.off-holiday {
  background: color-mix(in srgb, var(--danger) 14%, transparent);
}

.gantt-calendar-off.off-non_workday {
  background: color-mix(in srgb, var(--text-3) 20%, transparent);
}

.gantt-calendar-off.off-off_shift {
  background: color-mix(in srgb, var(--primary) 9%, transparent);
}

.gantt-idle {
  position: absolute;
  top: 4px;
  bottom: 4px;
  border-radius: 2px;
  background: repeating-linear-gradient(
    -45deg,
    color-mix(in srgb, var(--text-3) 16%, transparent),
    color-mix(in srgb, var(--text-3) 16%, transparent) 4px,
    color-mix(in srgb, var(--text-3) 6%, transparent) 4px,
    color-mix(in srgb, var(--text-3) 6%, transparent) 8px
  );
  pointer-events: none;
}

.gantt-links {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 3;
  pointer-events: none;
}

.gantt-link {
  fill: none;
  stroke: var(--primary);
  stroke-width: 1.2;
  opacity: 0.9;
}

.gantt-link.is-violated {
  stroke: var(--danger);
  stroke-width: 1.6;
  stroke-dasharray: 4 3;
}

.gantt-bar {
  position: absolute;
  top: 7px;
  height: 20px;
  border-radius: 3px;
  border: 1px solid transparent;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 var(--space-1);
  font-size: var(--font-xs);
  color: var(--text);
  overflow: hidden;
  cursor: default;
  box-sizing: border-box;
}

.gantt-bar__label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.gantt-bar__lock {
  flex: 0 0 auto;
  font-size: 10px;
  border: 1px solid color-mix(in srgb, var(--text) 70%, transparent);
  border-radius: 2px;
  padding: 0 2px;
  line-height: 12px;
}

.gantt-bar.tag-primary {
  background: var(--gantt-bar-run);
}

.gantt-bar.tag-success {
  background: var(--ok);
}

.gantt-bar.tag-warning {
  background: var(--warn);
}

.gantt-bar.tag-info {
  background: var(--text-3);
}

/* 冻结层：PRD BR-GT-001-06 要求灰色背景 + 锁标识，视觉上明确不可拖。 */
.gantt-bar.is-frozen {
  background: linear-gradient(180deg, var(--gantt-bar-frozen-light), var(--gantt-bar-frozen));
  border: 1px dashed var(--text-2);
  cursor: not-allowed;
}

.gantt-bar.is-conflict {
  outline: 2px solid var(--danger);
  outline-offset: -1px;
  z-index: 2;
}

/* 延期任务（C29 late）：左侧红条，与订单行头延期分钟同源。 */
.gantt-bar.is-late {
  box-shadow: inset 3px 0 0 var(--danger);
}

/* 工时窗锁 / 资源锁：锁标识在条内，视觉与订单冻结区分。 */
.gantt-bar.is-window-locked,
.gantt-bar.is-resource-locked {
  border: 1px solid var(--warn);
}

/* 任务占用落在资源日历非工作时段（订单视图 C14 校验）。 */
.gantt-bar.is-off-calendar {
  border: 1px dashed var(--warn);
}

.gantt-bar__off-calendar {
  flex: 0 0 auto;
  font-size: 10px;
  border: 1px solid color-mix(in srgb, var(--text) 70%, transparent);
  border-radius: 2px;
  padding: 0 2px;
  line-height: 12px;
}

/* 选中任务（M2-FE-03 调整面板目标）。 */
.gantt-bar.is-selected {
  outline: 2px solid var(--text);
  outline-offset: 1px;
  z-index: 3;
}

/* 键盘聚焦必须可见：覆盖 bracket 冲突/选中态，保证 Tab 后能看出当前任务条。 */
.gantt-bar:focus-visible {
  outline: 2px solid var(--primary-hover);
  outline-offset: 2px;
  z-index: 4;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary) 28%, transparent);
}

.gantt-body {
  cursor: pointer;
}

.gantt-setup {
  position: absolute;
  top: 27px;
  height: 4px;
  border-radius: 2px;
  background: repeating-linear-gradient(
    -45deg,
    var(--gantt-setup-stripe),
    var(--gantt-setup-stripe) 3px,
    var(--gantt-setup-fill) 3px,
    var(--gantt-setup-fill) 6px
  );
  box-sizing: border-box;
}

.gantt-tooltip {
  position: fixed;
  z-index: 10000;
  max-width: 360px;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--bg) 94%, transparent);
  color: var(--text);
  font-size: var(--font-xs);
  line-height: 18px;
  pointer-events: none;
  box-shadow: var(--shadow-sm);
}

.gantt-tooltip__title {
  font-weight: 600;
  margin-bottom: 2px;
}

.gantt-tooltip__conflict {
  color: var(--danger);
  border-top: 1px dashed color-mix(in srgb, var(--text) 24%, transparent);
  margin-top: 3px;
  padding-top: 3px;
}
</style>
