<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { getEnumLabel, getEnumTagType } from '@/constants/enums'
import { heatLevelOf } from './analysis'
import type {
  GanttAnalysis,
  GanttHeatCell,
  GanttIdleGap,
  GanttLayerFlags
} from './analysis'
import type {
  GanttCalendarRowState,
  GanttCalendarWindow,
  GanttCalendarWindowKind
} from './calendar'
import type { GanttBarModel, GanttDataset, GanttZoom } from './index'
import type { TaskStatus } from '@/types/gantt'

const props = withDefaults(
  defineProps<{
    dataset: GanttDataset
    zoom: GanttZoom
    rowHeight?: number
    labelWidth?: number
    height?: number
    /** 只读增强层分析结果；缺省时只渲染基础任务条。 */
    analysis?: GanttAnalysis | null
    /** 图层开关：日历停机（C14）/ 可插空档（推导）/ 依赖 / 热力 / 冲突 / 冻结。 */
    layers?: GanttLayerFlags
  }>(),
  {
    rowHeight: 34,
    labelWidth: 232,
    height: 460,
    analysis: null,
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

const scrollRef = ref<HTMLElement | null>(null)
const headsRef = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
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
const OVERSCAN_ROWS = 3

const rangeStart = computed(() => Date.parse(props.dataset.range.from))
const rangeEnd = computed(() => Date.parse(props.dataset.range.to))
const totalMs = computed(() => Math.max(rangeEnd.value - rangeStart.value, 3_600_000))
const pxPerMs = computed(() => ZOOM_PX_PER_DAY[props.zoom] / 86_400_000)
const timelineWidth = computed(() => Math.max(Math.round(totalMs.value * pxPerMs.value), 320))
const rows = computed(() => props.dataset.rows)
const totalHeight = computed(() => rows.value.length * props.rowHeight)
const startIndex = computed(() =>
  Math.max(0, Math.floor(scrollTop.value / props.rowHeight) - OVERSCAN_ROWS)
)
const endIndex = computed(() =>
  Math.min(
    rows.value.length,
    Math.ceil((scrollTop.value + props.height) / props.rowHeight) + OVERSCAN_ROWS
  )
)

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

const frozenSet = computed(() => {
  if (!props.layers.freeze || !props.analysis) {
    return new Set<number>()
  }
  return new Set(props.analysis.frozenTaskIds)
})

const conflictCountByRow = computed(() => {
  const map = new Map<string, number>()
  props.analysis?.conflicts.forEach((conflict) => {
    if (!conflict.resourceCode) {
      return
    }
    map.set(conflict.resourceCode, (map.get(conflict.resourceCode) ?? 0) + 1)
  })
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
      bars.push({ bar, left, width: Math.min(width, timelineWidth.value - left) })
      if (bar.setupStartTime && bar.setupEndTime) {
        const setupStart = Date.parse(bar.setupStartTime)
        const setupEnd = Date.parse(bar.setupEndTime)
        if (Number.isFinite(setupStart) && Number.isFinite(setupEnd) && setupEnd > setupStart) {
          const setupLeft = Math.max((setupStart - from) * px, 0)
          const setupWidth = Math.max((setupEnd - setupStart) * px, 1)
          if (setupLeft < timelineWidth.value) {
            setups.push({
              taskId: bar.taskId,
              left: setupLeft,
              width: Math.min(setupWidth, timelineWidth.value - setupLeft)
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
      calendarShort: calendarShortOf(calendarRowByKey.value.get(row.key)),
      calendarState: calendarRowByKey.value.get(row.key)?.state ?? null
    }
  })
})

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

onMounted(() => {
  if (scrollRef.value) {
    scrollTop.value = scrollRef.value.scrollTop
  }
})
</script>

<template>
  <div class="gantt-timeline" :style="{ height: `${props.height}px` }">
    <div class="gantt-heads" ref="headsRef" :style="{ width: `${props.labelWidth}px` }">
      <div class="gantt-heads__corner">资源泳道</div>
      <div class="gantt-heads__canvas" :style="{ height: `${totalHeight}px` }">
        <div
          v-for="item in renderRows"
          :key="item.row.key"
          class="gantt-heads__row"
          :style="{ top: `${item.top}px`, height: `${props.rowHeight}px` }"
        >
          <span class="gantt-heads__name" :title="item.row.label">{{ item.row.label }}</span>
          <span class="gantt-heads__meta"
            >{{ item.row.key }} · {{ item.row.subLabel ?? '—' }} ·
            {{ item.row.bars.length }} 条<span
              v-if="conflictCountByRow.get(item.row.key)"
              class="gantt-heads__conflict"
              > · 冲突 {{ conflictCountByRow.get(item.row.key) }}</span
            >
            · <span
              class="gantt-heads__calendar"
              :class="item.calendarState ? `is-${item.calendarState.toLowerCase()}` : 'is-none'"
              >{{ item.calendarShort }}</span
            ></span
          >
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
        >
          <div class="gantt-grid" :style="gridStyle" />
          <div
            v-for="item in renderRows"
            :key="item.row.key"
            class="gantt-row"
            :style="{ top: `${item.top}px`, height: `${props.rowHeight}px` }"
          >
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
                  'is-frozen': frozenSet.has(entry.bar.taskId),
                  'is-conflict': conflictsOf(entry.bar.taskId).length > 0
                }
              ]"
              :style="{ left: `${entry.left}px`, width: `${entry.width}px` }"
              :data-task-id="entry.bar.taskId"
              :title="
                frozenSet.has(entry.bar.taskId)
                  ? '已冻结：只读，不可拖拽（拖拽属 M2-FE-03）'
                  : undefined
              "
            >
              <span v-if="frozenSet.has(entry.bar.taskId)" class="gantt-bar__lock">冻</span>
              <span class="gantt-bar__label">{{
                entry.bar.operationName ?? entry.bar.orderNo
              }}</span>
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
                <path d="M0,0 L6,3 L0,6 Z" fill="#409eff" />
              </marker>
              <marker
                id="gantt-arrow-violated"
                markerWidth="6"
                markerHeight="6"
                refX="5"
                refY="3"
                orient="auto"
              >
                <path d="M0,0 L6,3 L0,6 Z" fill="#f56c6c" />
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
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
  position: relative;
}

.gantt-heads {
  flex: 0 0 auto;
  overflow: hidden;
  border-right: 1px solid #dcdfe6;
  background: #fafafa;
}

.gantt-heads__corner {
  position: sticky;
  top: 0;
  z-index: 3;
  height: 44px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  background: #f2f4f7;
  border-bottom: 1px solid #dcdfe6;
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
  padding: 0 12px;
  border-bottom: 1px solid #ebeef5;
  overflow: hidden;
}

.gantt-heads__name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gantt-heads__meta {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gantt-heads__conflict {
  color: #f56c6c;
  font-weight: 600;
}

.gantt-heads__calendar {
  font-weight: 600;
}

.gantt-heads__calendar.is-bound {
  color: #67c23a;
}

.gantt-heads__calendar.is-unbound {
  color: #e6a23c;
}

.gantt-heads__calendar.is-missing,
.gantt-heads__calendar.is-error {
  color: #f56c6c;
}

.gantt-heads__calendar.is-none {
  color: #c0c4cc;
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
  background: #f2f4f7;
  border-bottom: 1px solid #dcdfe6;
}

.gantt-axis__tick {
  position: absolute;
  top: 0;
  height: 100%;
  border-left: 1px solid #dcdfe6;
}

.gantt-axis__tick.is-major {
  border-left-color: #c0c4cc;
}

.gantt-axis__label {
  position: absolute;
  top: 8px;
  left: 4px;
  font-size: 11px;
  color: #606266;
  white-space: nowrap;
}

.gantt-axis__tick.is-major .gantt-axis__label {
  color: #303133;
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
  background-image: linear-gradient(to right, #ebeef5 1px, transparent 1px);
  pointer-events: none;
}

.gantt-row {
  position: absolute;
  left: 0;
  right: 0;
  border-bottom: 1px solid #f2f3f5;
}

.gantt-row:nth-child(even) {
  background: rgba(250, 250, 250, 0.6);
}

.gantt-heat {
  position: absolute;
  top: 0;
  bottom: 0;
  pointer-events: none;
}

.gantt-heat.heat-green {
  background: rgba(103, 194, 58, 0.16);
}

.gantt-heat.heat-yellow {
  background: rgba(230, 162, 60, 0.24);
}

.gantt-heat.heat-red {
  background: rgba(245, 108, 108, 0.28);
}

/* 日历停机层（C14 事实）：班次休息 / 非排班日 / 非工作例外日，均来自资源工作日历。 */
.gantt-calendar-off {
  position: absolute;
  top: 0;
  bottom: 0;
  pointer-events: none;
}

.gantt-calendar-off.off-holiday {
  background: rgba(245, 108, 108, 0.14);
}

.gantt-calendar-off.off-non_workday {
  background: rgba(144, 147, 153, 0.2);
}

.gantt-calendar-off.off-off_shift {
  background: rgba(64, 158, 255, 0.09);
}

.gantt-idle {
  position: absolute;
  top: 4px;
  bottom: 4px;
  border-radius: 2px;
  background: repeating-linear-gradient(
    -45deg,
    rgba(144, 147, 153, 0.16),
    rgba(144, 147, 153, 0.16) 4px,
    rgba(144, 147, 153, 0.06) 4px,
    rgba(144, 147, 153, 0.06) 8px
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
  stroke: #409eff;
  stroke-width: 1.2;
  opacity: 0.9;
}

.gantt-link.is-violated {
  stroke: #f56c6c;
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
  padding: 0 4px;
  font-size: 11px;
  color: #fff;
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
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 2px;
  padding: 0 2px;
  line-height: 12px;
}

.gantt-bar.tag-primary {
  background: #409eff;
}

.gantt-bar.tag-success {
  background: #67c23a;
}

.gantt-bar.tag-warning {
  background: #e6a23c;
}

.gantt-bar.tag-info {
  background: #909399;
}

/* 冻结层：PRD BR-GT-001-06 要求灰色背景 + 锁标识，视觉上明确不可拖。 */
.gantt-bar.is-frozen {
  background: linear-gradient(180deg, #b4b6bb, #909399);
  border: 1px dashed #606266;
  cursor: not-allowed;
}

.gantt-bar.is-conflict {
  outline: 2px solid #f56c6c;
  outline-offset: -1px;
  z-index: 2;
}

.gantt-setup {
  position: absolute;
  top: 27px;
  height: 4px;
  border-radius: 2px;
  background: repeating-linear-gradient(-45deg, #c8c9cc, #c8c9cc 3px, #e9e9eb 3px, #e9e9eb 6px);
  box-sizing: border-box;
}

.gantt-tooltip {
  position: fixed;
  z-index: 10000;
  max-width: 360px;
  padding: 8px 10px;
  border-radius: 4px;
  background: rgba(48, 49, 51, 0.94);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  pointer-events: none;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
}

.gantt-tooltip__title {
  font-weight: 600;
  margin-bottom: 2px;
}

.gantt-tooltip__conflict {
  color: #ffb3b3;
  border-top: 1px dashed rgba(255, 255, 255, 0.24);
  margin-top: 3px;
  padding-top: 3px;
}
</style>
