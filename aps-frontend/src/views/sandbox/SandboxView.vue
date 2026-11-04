<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

import { ApiError, createIdempotencyKey } from '@/api/http'
import { applySandbox, compareSandbox, createSandbox, listSandboxes } from '@/api/orders'
import { DataTable } from '@/components/common'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { Sandbox, SandboxApplyResult, SandboxCompare, SandboxChangeType } from '@/types/orders'

const rows = ref<Sandbox[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const typeFilter = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createForm = reactive({ name: '', expiresAt: '' })

const applyVisible = ref(false)
const applying = ref(false)
const applyError = ref('')
const applyTarget = ref<Sandbox | null>(null)
const applyReason = ref('')
const applyResult = ref<SandboxApplyResult | null>(null)

const compareLoading = ref(false)
const compareError = ref('')
const compareResult = ref<SandboxCompare | null>(null)
const compareSourceId = ref('')
const compareTargetId = ref('')

const baseline = computed(() => rows.value.find((item) => item.type === 'BASELINE') ?? null)
const whatIfOptions = computed(() => rows.value.filter((item) => item.type === 'WHAT_IF'))

function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

function signedMinutes(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value} min`
}

function utilizationText(value: number): string {
  return value.toFixed(3)
}

function deltaClass(value: number): string {
  if (value > 0) {
    return 'delta-negative'
  }
  if (value < 0) {
    return 'delta-positive'
  }
  return 'delta-neutral'
}

function changeTypeLabel(type: SandboxChangeType): string {
  return { ADDED: '新增', REMOVED: '移除', MOVED: '移动' }[type]
}

async function loadSandboxes() {
  loading.value = true
  error.value = ''
  try {
    const result = await listSandboxes({
      page: page.value,
      size: size.value,
      type: typeFilter.value || undefined
    })
    rows.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size
    if (!compareTargetId.value) {
      compareTargetId.value = baseline.value?.id ?? ''
    }
  } catch (exception) {
    rows.value = []
    total.value = 0
    error.value = exception instanceof Error ? exception.message : '场景列表加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadSandboxes()
}

function openCreate() {
  createForm.name = ''
  createForm.expiresAt = ''
  createError.value = ''
  createVisible.value = true
}

async function submitCreate() {
  createError.value = ''
  if (!createForm.name.trim()) {
    createError.value = '场景名称不能为空'
    return
  }
  creating.value = true
  try {
    // C32：创建只允许 WHAT_IF；父场景缺省为主线 BASELINE。
    await createSandbox(
      {
        name: createForm.name.trim(),
        type: 'WHAT_IF',
        parentSandboxId: DEFAULT_SANDBOX_ID,
        expiresAt: createForm.expiresAt || null
      },
      createIdempotencyKey()
    )
    ElMessage.success('WHAT_IF 场景已创建')
    createVisible.value = false
    await loadSandboxes()
  } catch (exception) {
    createError.value = exception instanceof Error ? exception.message : '创建场景失败'
  } finally {
    creating.value = false
  }
}

function openApply(row: Sandbox) {
  applyTarget.value = row
  applyReason.value = ''
  applyError.value = ''
  applyResult.value = null
  applyVisible.value = true
}

async function submitApply() {
  if (!applyTarget.value) {
    return
  }
  applyError.value = ''
  if (!applyReason.value.trim()) {
    applyError.value = '请填写应用原因（后端会写入审计）'
    return
  }
  applying.value = true
  try {
    applyResult.value = await applySandbox(
      applyTarget.value.id,
      applyReason.value.trim(),
      createIdempotencyKey()
    )
    ElMessage.success('场景已提升为 BASELINE，并发布新计划版本')
    await loadSandboxes()
  } catch (exception) {
    applyError.value =
      exception instanceof ApiError && exception.code === 42200
        ? exception.message
        : exception instanceof Error
          ? exception.message
          : '应用场景失败'
  } finally {
    applying.value = false
  }
}

async function runCompare(sourceId: string, targetId: string) {
  compareError.value = ''
  compareResult.value = null
  if (!sourceId || !targetId) {
    compareError.value = '请选择要对比的场景与目标场景'
    return
  }
  if (sourceId === targetId) {
    compareError.value = '源场景与目标场景不能相同'
    return
  }
  compareLoading.value = true
  try {
    compareResult.value = await compareSandbox(sourceId, targetId)
  } catch (exception) {
    compareError.value = exception instanceof Error ? exception.message : '场景对比失败'
  } finally {
    compareLoading.value = false
  }
}

onMounted(() => {
  void loadSandboxes()
})

function compareWithBaseline(row: Sandbox) {
  if (!baseline.value) {
    ElMessage.warning('当前列表没有 BASELINE 场景可作为对比目标')
    return
  }
  compareSourceId.value = row.id
  compareTargetId.value = baseline.value.id
  void runCompare(row.id, baseline.value.id)
}
</script>

<template>
  <div>
    <h2 class="page-title">场景沙箱</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="创建只允许 WHAT_IF；apply 会单事务发布新版本并把该场景提升为 BASELINE"
      description="compare 方向固定为「当前场景 − 目标场景」；dueDateDeltaMinutes 单位为分钟、可为负；utilizationDelta 为 0–1 小数并保留 3 位。"
    />

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>场景列表</span>
          <span class="muted">ARCHIVED 场景不在列表返回范围内</span>
        </div>
      </template>
      <div class="toolbar-row">
        <el-select v-model="typeFilter" clearable placeholder="全部类型" style="width: 180px">
          <el-option label="BASELINE" value="BASELINE" />
          <el-option label="WHAT_IF" value="WHAT_IF" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="openCreate">新建 WHAT_IF 场景</el-button>
      </div>

      <DataTable
        v-model:page="page"
        v-model:size="size"
        :data="rows"
        :loading="loading"
        :error="error"
        :total="total"
        row-key="id"
        empty-text="暂无场景"
        @change="loadSandboxes"
        @retry="loadSandboxes"
      >
        <el-table-column prop="name" label="场景名称" min-width="180" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.type === 'BASELINE' ? 'success' : 'warning'" size="small">
              {{ row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="父场景" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ row.parentSandboxId || '—' }}</template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.type === 'WHAT_IF'">
              <el-button link type="primary" @click="compareWithBaseline(row)">对比基线</el-button>
              <el-button link type="warning" @click="openApply(row)">Apply</el-button>
            </template>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>场景对比</span>
          <span class="muted">GET /sandboxes/{id}/compare?targetId=</span>
        </div>
      </template>
      <div class="compare-form">
        <div class="filter-field">
          <label class="field-label" for="compare-source">源场景（WHAT_IF）</label>
          <el-select id="compare-source" v-model="compareSourceId" clearable placeholder="选择源场景" style="width: 100%">
            <el-option
              v-for="item in whatIfOptions"
              :key="item.id"
              :label="`${item.name}（v${item.version}）`"
              :value="item.id"
            />
          </el-select>
        </div>
        <div class="filter-field">
          <label class="field-label" for="compare-target">目标场景</label>
          <el-select id="compare-target" v-model="compareTargetId" clearable placeholder="选择目标场景" style="width: 100%">
            <el-option
              v-for="item in rows"
              :key="item.id"
              :label="`${item.name}（${item.type}）`"
              :value="item.id"
            />
          </el-select>
        </div>
        <el-button
          type="primary"
          :loading="compareLoading"
          @click="runCompare(compareSourceId, compareTargetId)"
        >
          对比
        </el-button>
      </div>

      <el-alert v-if="compareError" type="error" :closable="false" :title="compareError" class="inline-alert" />

      <template v-if="compareResult">
        <el-alert
          v-if="compareResult.truncated"
          type="warning"
          :closable="false"
          show-icon
          class="inline-alert"
          title="结果被截断：下方明细不是全量"
          :description="`计数仍为全量真实值（新增 ${compareResult.addedTaskCount} / 移除 ${compareResult.removedTaskCount} / 移动 ${compareResult.movedTaskCount}），明细仅展示已返回的 ${compareResult.changedTasks.length} 条。`"
        />

        <div class="summary-grid">
          <div class="summary-card">
            <p class="summary-label">新增任务</p>
            <p class="summary-value">{{ compareResult.addedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移除任务</p>
            <p class="summary-value">{{ compareResult.removedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移动任务</p>
            <p class="summary-value">{{ compareResult.movedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">交期差（分钟，正=更晚）</p>
            <p class="summary-value" :class="deltaClass(compareResult.dueDateDeltaMinutes)">
              {{ signedMinutes(compareResult.dueDateDeltaMinutes) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">产能利用率差（0–1）</p>
            <p class="summary-value" :class="deltaClass(compareResult.utilizationDelta)">
              {{ utilizationText(compareResult.utilizationDelta) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">换型次数差</p>
            <p class="summary-value" :class="deltaClass(compareResult.setupCountDelta)">
              {{ compareResult.setupCountDelta > 0 ? '+' : '' }}{{ compareResult.setupCountDelta }}
            </p>
          </div>
        </div>

        <p class="direction-hint">
          对比方向：{{ compareResult.sandboxId }} − {{ compareResult.targetId }}
        </p>

        <DataTable :data="compareResult.changedTasks" :show-pagination="false" row-key="taskId">
          <el-table-column prop="taskId" label="taskId" width="110" />
          <el-table-column prop="orderNo" label="订单号" min-width="160" />
          <el-table-column prop="resourceCode" label="资源" min-width="160" />
          <el-table-column label="changeType" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.changeType === 'ADDED' ? 'success' : row.changeType === 'REMOVED' ? 'danger' : 'warning'">
                {{ row.changeType }} · {{ changeTypeLabel(row.changeType) }}
              </el-tag>
            </template>
          </el-table-column>
        </DataTable>
      </template>
    </el-card>

    <el-dialog v-model="createVisible" title="新建 WHAT_IF 场景" width="520px" destroy-on-close>
      <el-alert v-if="createError" type="error" :closable="false" :title="createError" class="dialog-error" />
      <el-form label-position="top">
        <el-form-item label="场景名称">
          <el-input v-model="createForm.name" maxlength="80" />
        </el-form-item>
        <el-form-item label="过期时间（可空）">
          <el-date-picker
            v-model="createForm.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="留空表示不过期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-input model-value="WHAT_IF（创建时固定）" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="applyVisible" title="应用场景（提升为 BASELINE）" width="560px" destroy-on-close>
      <el-alert v-if="applyError" type="error" :closable="false" :title="applyError" class="dialog-error" />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="此操作会发布新计划版本，并将原 BASELINE 归档"
        class="dialog-error"
      />
      <el-form label-position="top">
        <el-form-item label="场景">
          <el-input :model-value="applyTarget?.name ?? ''" disabled />
        </el-form-item>
        <el-form-item label="应用原因 reason（必填，写入审计）">
          <el-input v-model="applyReason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <el-descriptions v-if="applyResult" :column="2" border size="small" class="apply-result">
        <el-descriptions-item label="新版本号">v{{ applyResult.plan.version }}</el-descriptions-item>
        <el-descriptions-item label="受影响任务数">{{ applyResult.plan.affectedTaskCount }}</el-descriptions-item>
        <el-descriptions-item label="计划号">{{ applyResult.plan.planNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ applyResult.plan.status }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="applyVisible = false">关闭</el-button>
        <el-button type="primary" :loading="applying" @click="submitApply">确认应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 var(--space-4);
  font-size: var(--font-title);
  color: var(--text);
}

.hint-alert,
.section {
  margin-bottom: var(--space-4);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.muted {
  color: var(--text-2);
  font-size: var(--font-xs);
}

.toolbar-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-3);
}

.compare-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
  gap: var(--space-3);
  align-items: end;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: var(--font-xs);
  color: var(--text-2);
}

.inline-alert {
  margin-top: var(--space-3);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.summary-card {
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  border: var(--border-width) solid var(--border);
}

.summary-label {
  margin: 0;
  font-size: var(--font-xs);
  color: var(--text-2);
}

.summary-value {
  margin: var(--space-2) 0 0;
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--text);
}

.delta-positive {
  color: var(--ok);
}

.delta-negative {
  color: var(--danger);
}

.delta-neutral {
  color: var(--text-2);
}

.direction-hint {
  margin: var(--space-3) 0;
  font-size: var(--font-xs);
  color: var(--text-2);
  word-break: break-all;
}

.dialog-error {
  margin-bottom: var(--space-3);
}

.apply-result {
  margin-top: var(--space-3);
}
</style>
