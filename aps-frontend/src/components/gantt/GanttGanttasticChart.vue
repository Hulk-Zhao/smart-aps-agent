<script setup lang="ts">
import { GGanttChart, GGanttRow } from '@infectoone/vue-ganttastic'
import dayjs from 'dayjs'
import { computed } from 'vue'

import type { GanttBarModel, GanttDataset, GanttZoom } from './index'
import type { TaskStatus } from '@/types/gantt'

const props = withDefaults(
  defineProps<{
    dataset: GanttDataset
    zoom: GanttZoom
    rowHeight?: number
  }>(),
  { rowHeight: 34 }
)

const STATUS_BACKGROUND: Record<TaskStatus, string> = {
  PLANNED: '#409eff',
  FROZEN: '#e6a23c',
  IN_PROGRESS: '#67c23a',
  COMPLETED: '#909399'
}

interface GanttasticBar {
  start: string
  end: string
  ganttBarConfig: {
    id: string
    label?: string
    immobile: boolean
    style: Record<string, string>
  }
}

/**
 * vue-ganttastic 用 dayjs 严格模式按默认格式 `YYYY-MM-DD HH:mm` 解析时间，
 * ISO 8601（带 T/Z）会得到 Invalid Date，因此这里统一转成本地时区的该格式。
 */
function toChartTime(value: string): string {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

function toBars(bar: GanttBarModel): GanttasticBar[] {
  const items: GanttasticBar[] = []
  if (
    bar.setupStartTime &&
    bar.setupEndTime &&
    Date.parse(bar.setupEndTime) > Date.parse(bar.setupStartTime)
  ) {
    items.push({
      start: toChartTime(bar.setupStartTime),
      end: toChartTime(bar.setupEndTime),
      ganttBarConfig: {
        id: `setup-${bar.taskId}`,
        immobile: true,
        style: {
          background:
            'repeating-linear-gradient(-45deg, #c8c9cc, #c8c9cc 3px, #e9e9eb 3px, #e9e9eb 6px)',
          borderRadius: '2px',
          height: '6px'
        }
      }
    })
  }
  items.push({
    start: toChartTime(bar.startTime),
    end: toChartTime(bar.endTime),
    ganttBarConfig: {
      id: `run-${bar.taskId}`,
      label: bar.operationName ?? bar.orderNo,
      immobile: true,
      style: {
        background: STATUS_BACKGROUND[bar.status],
        borderRadius: '3px',
        color: '#fff',
        fontSize: '11px'
      }
    }
  })
  return items
}

const rows = computed(() =>
  props.dataset.rows.map((row) => ({
    key: row.key,
    label: `${row.label} · ${row.key}`,
    bars: row.bars.flatMap(toBars)
  }))
)
</script>

<template>
  <div class="gantt-ganttastic">
    <GGanttChart
      :chart-start="toChartTime(dataset.range.from)"
      :chart-end="toChartTime(dataset.range.to)"
      :precision="props.zoom"
      bar-start="start"
      bar-end="end"
      :row-height="props.rowHeight"
      :push-on-overlap="false"
      :no-overlap="false"
      :current-time="false"
      label-column-width="220px"
    >
      <GGanttRow v-for="row in rows" :key="row.key" :label="row.label" :bars="row.bars" />
    </GGanttChart>
  </div>
</template>

<style scoped>
.gantt-ganttastic {
  width: 100%;
  max-height: 560px;
  overflow: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}
</style>
