<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { cancelSolveTask, createSolveProgressClient, getSolveTask, submitSolveTask } from '@/api'
import type { SolveProgressChannelState, SolveProgressClient } from '@/api/solve'
import { StatusTag } from '@/components/common'
import type {
  SolveProgress,
  SolveTaskDetail,
  SolveTaskStatus,
  SolverEngine
} from '@/types/solve'

const POLL_INTERVAL_MS = 2000
const TERMINAL_STATUSES: SolveTaskStatus[] = ['SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT']

const form = reactive<{ taskNo: string; engine: SolverEngine; orderNos: string }>({
  taskNo: 'DEMO-001',
  engine: 'timefold',
  orderNos: ''
})

const submitting = ref(false)
const cancelling = ref(false)
const error = ref('')
const detail = ref<SolveTaskDetail | null>(null)
const progress = ref<SolveProgress | null>(null)
const channelState = ref<SolveProgressChannelState | 'IDLE'>('IDLE')
const channelWarning = ref('')

let progressClient: SolveProgressClient | null = null
let pollTimer: number | null = null
let pollInFlight = false

const currentStatus = computed<SolveTaskStatus>(() => detail.value?.status ?? 'PENDING')
const currentProgress = computed(() => {
  const status = currentStatus.value
  if (status === 'SOLVED') {
    return 1
  }
  if (TERMINAL_STATUSES.includes(status)) {
    return 0
  }
  return progress.value?.progress ?? detail.value?.progress ?? 0
})
const percent = computed(() =>
  Math.round(Math.min(1, Math.max(0, currentProgress.value)) * 100)
)
const barStatus = computed<'success' | 'exception' | undefined>(() => {
  if (currentStatus.value === 'SOLVED') {
    return 'success'
  }
  if (TERMINAL_STATUSES.includes(currentStatus.value)) {
    return 'exception'
  }
  return undefined
})
const phase = computed(() => progress.value?.phase ?? detail.value?.phase ?? '—')
const bestScore = computed(() => progress.value?.bestScore ?? detail.value?.bestScore ?? '—')
const elapsedMs = computed(
  () => progress.value?.elapsedMs ?? detail.value?.elapsedMs ?? null
)
const isTerminal = computed(() => TERMINAL_STATUSES.includes(currentStatus.value))

const degraded = computed(() => detail.value?.metrics?.engineFallback === true)
const degradedReason = computed(() => {
  const reason = detail.value?.metrics?.engineFallbackReason
  return typeof reason === 'string' ? reason : ''
})

const channelTag = computed<{ type: 'success' | 'info' | 'warning'; label: string }>(() => {
  switch (channelState.value) {
    case 'CONNECTED':
      return { type: 'success', label: 'STOMP 已连接' }
    case 'CONNECTING':
      return { type: 'info', label: 'STOMP 连接中 · 2s 轮询兜底' }
    case 'DISCONNECTED':
      return { type: 'warning', label: 'STOMP 已断开 · 2s 轮询兜底' }
    case 'DISABLED':
      return { type: 'info', label: 'Mock 模式 · 2s 轮询兜底' }
    default:
      return { type: 'info', label: '未开始' }
  }
})

function isTerminalStatus(status: SolveTaskStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

function formatElapsed(value: number | null): string {
  if (value === null || value <= 0) {
    return '—'
  }
  if (value < 1000) {
    return `${value} ms`
  }
  return `${(value / 1000).toFixed(1)} s`
}

async function refreshDetail() {
  if (!detail.value) {
    return
  }
  try {
    detail.value = await getSolveTask(detail.value.id)
    if (isTerminalStatus(detail.value.status)) {
      stopTracking()
    }
  } catch {
    // 查询失败时保留上一份事实，等待 STOMP 或下一轮轮询
  }
}

function startPolling() {
  if (pollTimer !== null) {
    return
  }
  pollTimer = window.setInterval(() => {
    void pollOnce()
  }, POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollOnce() {
  if (!detail.value || pollInFlight) {
    return
  }
  pollInFlight = true
  try {
    detail.value = await getSolveTask(detail.value.id)
    if (isTerminalStatus(detail.value.status)) {
      stopTracking()
    }
  } catch {
    // 保留上一份事实
  } finally {
    pollInFlight = false
  }
}

/** STOMP 仅推送；GET /solve-tasks/{id} 是事实源，连接中/断线均按 2 秒轮询兜底。 */
function startTracking(task: SolveTaskDetail) {
  stopTracking()
  detail.value = task
  progress.value = null
  channelWarning.value = ''
  channelState.value = 'CONNECTING'
  startPolling()

  progressClient = createSolveProgressClient(task.id, {
    onMessage: (message) => {
      progress.value = message
      if (isTerminalStatus(message.status)) {
        void refreshDetail()
      }
    },
    onStateChange: (state) => {
      channelState.value = state
      if (state === 'CONNECTED') {
        stopPolling()
        void refreshDetail()
      } else {
        startPolling()
      }
    },
    onError: (message) => {
      channelWarning.value = message
    }
  })
}

function stopTracking() {
  stopPolling()
  if (progressClient) {
    progressClient.disconnect()
    progressClient = null
  }
}

async function onSubmit() {
  submitting.value = true
  error.value = ''
  stopTracking()
  detail.value = null
  progress.value = null
  channelState.value = 'IDLE'
  try {
    const orderNos = form.orderNos
      .split(',')
      .map((orderNo) => orderNo.trim())
      .filter((orderNo) => orderNo.length > 0)
    const created = await submitSolveTask({
      taskNo: form.taskNo,
      engine: form.engine,
      orderNos,
      parameters: {}
    })
    if (typeof created?.id !== 'number') {
      error.value = '接口未按 C20 返回 SolveTaskDetail（缺少 id），无法订阅进度'
      return
    }
    startTracking(created)
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '求解请求失败'
  } finally {
    submitting.value = false
  }
}

async function onCancel() {
  if (!detail.value) {
    return
  }
  cancelling.value = true
  try {
    detail.value = await cancelSolveTask(detail.value.id)
    ElMessage.success('求解任务已取消')
    if (isTerminalStatus(detail.value.status)) {
      stopTracking()
    }
  } catch {
    // http 拦截器已统一提示
  } finally {
    cancelling.value = false
  }
}

onBeforeUnmount(() => {
  stopTracking()
})
</script>

<template>
  <div>
    <h2 class="page-title">排产求解</h2>
    <el-card class="page-card" shadow="never">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="任务号">
          <el-input v-model="form.taskNo" placeholder="请输入求解任务号" />
        </el-form-item>
        <el-form-item label="求解引擎">
          <el-select v-model="form.engine">
            <el-option label="Timefold（默认）" value="timefold" />
            <el-option label="OR-Tools（预留）" value="ortools" />
            <el-option label="规则引擎（兼容）" value="rule_based" />
          </el-select>
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="form.orderNos" placeholder="多个订单号以英文逗号分隔" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交求解</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="error" class="page-card" type="error" :closable="false" :title="error" />

    <el-card v-if="detail" shadow="never">
      <template #header>
        <div class="panel-header">
          <span>求解进度 #{{ detail.id }}</span>
          <div class="panel-tags">
            <StatusTag enum-key="solveTaskStatus" :value="currentStatus" />
            <el-tag :type="channelTag.type" size="small">{{ channelTag.label }}</el-tag>
            <el-tooltip v-if="degraded" :content="degradedReason || '引擎降级求解'">
              <el-tag type="warning" size="small">降级</el-tag>
            </el-tooltip>
          </div>
        </div>
      </template>

      <el-progress :percentage="percent" :status="barStatus" :stroke-width="16" />
      <el-alert
        v-if="channelWarning"
        class="channel-warning"
        type="warning"
        :closable="false"
        :title="channelWarning"
      />

      <el-descriptions class="panel-detail" :column="3" border>
        <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="引擎">{{ detail.engine }}</el-descriptions-item>
        <el-descriptions-item label="沙箱">{{ detail.sandboxId }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{ phase }}</el-descriptions-item>
        <el-descriptions-item label="最优分数">{{ bestScore }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatElapsed(elapsedMs) }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ detail.startedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ detail.finishedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结果任务">
          {{ detail.resultTaskId ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="说明" :span="3">
          {{ detail.errorMessage || progress?.message || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="panel-actions">
        <el-button :disabled="isTerminal" :loading="cancelling" @click="onCancel">
          取消求解
        </el-button>
        <el-button @click="refreshDetail">立即刷新</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-tags {
  display: flex;
  gap: 8px;
}

.channel-warning,
.panel-detail {
  margin-top: 12px;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
