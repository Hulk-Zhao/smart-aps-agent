<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { cloneStrategy, getStrategy, listStrategies } from '@/api/orders'
import { DataTable, StatusTag } from '@/components/common'
import type { Strategy, StrategyCreateRequest, StrategyDetail } from '@/types/orders'

import StrategyEditorDrawer from './StrategyEditorDrawer.vue'
import { SOFT_WEIGHT_TOTAL, sumEnabledSoftWeights } from './rule-catalog'

const strategies = ref<Strategy[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingStrategy = ref<StrategyDetail | null>(null)
const importDraft = ref<StrategyCreateRequest | null>(null)
const importing = ref(false)
const importInput = ref<HTMLInputElement | null>(null)

async function loadStrategies() {
  loading.value = true
  error.value = ''
  try {
    const result = await listStrategies({ page: page.value, size: size.value })
    strategies.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '策略列表加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editorMode.value = 'create'
  editingStrategy.value = null
  importDraft.value = null
  editorVisible.value = true
}

async function openEdit(row: Strategy) {
  loading.value = true
  try {
    editingStrategy.value = await getStrategy(row.id)
    editorMode.value = 'edit'
    importDraft.value = null
    editorVisible.value = true
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '策略详情加载失败'
  } finally {
    loading.value = false
  }
}

async function onClone(row: Strategy) {
  try {
    await cloneStrategy(row.id)
    ElMessage.success(`已克隆策略「${row.name}」`)
    await loadStrategies()
  } catch {
    // http 拦截器已统一提示
  }
}

async function onExport(row: Strategy) {
  try {
    const detail = await getStrategy(row.id)
    const payload = {
      name: detail.name,
      direction: detail.direction,
      strategyVersion: detail.strategyVersion,
      effectiveFrom: detail.effectiveFrom,
      effectiveTo: detail.effectiveTo ?? null,
      initialRules: detail.initialRules ?? {},
      optimizationRules: detail.optimizationRules ?? {},
      applyCondition: detail.applyCondition ?? {},
      rules: detail.rules
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], {
      type: 'application/json;charset=utf-8'
    })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${sanitizeFileName(detail.name)}.json`
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('策略 JSON 已导出')
  } catch {
    // http 拦截器已统一提示
  }
}

function sanitizeFileName(name: string): string {
  return name.replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 64) || 'strategy'
}

function onImportClick() {
  importInput.value?.click()
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 导入 JSON：仅做结构预校验，规则合法性与权重和仍由保存时校验兜底。 */
function parseImportPayload(text: string): StrategyCreateRequest {
  const data: unknown = JSON.parse(text)
  if (!isRecord(data)) {
    throw new Error('导入文件必须是 JSON 对象')
  }
  if (typeof data.name !== 'string' || !data.name.trim()) {
    throw new Error('导入文件缺少策略名称 name')
  }
  const direction = data.direction
  if (
    typeof direction !== 'string' ||
    !['FORWARD', 'BACKWARD', 'BOTTLENECK'].includes(direction)
  ) {
    throw new Error('导入文件的 direction 必须为 FORWARD/BACKWARD/BOTTLENECK')
  }
  if (typeof data.effectiveFrom !== 'string' || !data.effectiveFrom) {
    throw new Error('导入文件缺少生效日期 effectiveFrom')
  }
  if (data.rules !== undefined && !Array.isArray(data.rules)) {
    throw new Error('导入文件的 rules 必须为数组')
  }
  return {
    name: data.name.trim(),
    direction: direction as StrategyCreateRequest['direction'],
    strategyVersion: Number(data.strategyVersion) || undefined,
    effectiveFrom: data.effectiveFrom,
    effectiveTo: typeof data.effectiveTo === 'string' ? data.effectiveTo : null,
    initialRules: isRecord(data.initialRules) ? data.initialRules : {},
    optimizationRules: isRecord(data.optimizationRules) ? data.optimizationRules : {},
    applyCondition: isRecord(data.applyCondition) ? data.applyCondition : {},
    rules: Array.isArray(data.rules) ? data.rules : undefined
  }
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  importing.value = true
  try {
    const text = await file.text()
    importDraft.value = parseImportPayload(text)
    editorMode.value = 'create'
    editingStrategy.value = null
    editorVisible.value = true
    ElMessage.success('导入文件解析成功，请确认后保存')
  } catch (exception) {
    ElMessage.error(
      exception instanceof Error ? exception.message : '导入文件解析失败'
    )
  } finally {
    importing.value = false
  }
}

function softWeightOf(row: Strategy): number | null {
  if (!row.rules || row.rules.length === 0) {
    return null
  }
  return sumEnabledSoftWeights(row.rules)
}

function onSaved() {
  void loadStrategies()
}

onMounted(() => {
  void loadStrategies()
})
</script>

<template>
  <div>
    <h2 class="page-title">排产策略</h2>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="strategies"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="id"
      empty-text="暂无排产策略"
      @change="loadStrategies"
      @retry="loadStrategies"
    >
      <template #toolbar>
        <input
          ref="importInput"
          class="file-input"
          type="file"
          accept="application/json,.json"
          @change="onImportFile"
        />
        <el-button type="primary" @click="openCreate">新建策略</el-button>
        <el-button :loading="importing" @click="onImportClick">导入 JSON</el-button>
        <el-button @click="loadStrategies">刷新</el-button>
      </template>

      <el-table-column prop="name" label="策略名称" min-width="180" />
      <el-table-column label="排产方向" width="120">
        <template #default="{ row }">
          <StatusTag enum-key="strategyDirection" :value="row.direction" />
        </template>
      </el-table-column>
      <el-table-column prop="strategyVersion" label="业务版本" width="100" />
      <el-table-column label="生效期" min-width="200">
        <template #default="{ row }">
          {{ row.effectiveFrom }} ~ {{ row.effectiveTo || '长期' }}
        </template>
      </el-table-column>
      <el-table-column label="规则数" width="90">
        <template #default="{ row }">{{ row.rules?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="软约束权重和" width="140">
        <template #default="{ row }">
          <el-tag
            v-if="softWeightOf(row) !== null"
            :type="Math.abs((softWeightOf(row) ?? 0) - SOFT_WEIGHT_TOTAL) < 1e-6 ? 'success' : 'danger'"
            size="small"
          >
            {{ softWeightOf(row) }} / {{ SOFT_WEIGHT_TOTAL }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="onClone(row)">克隆</el-button>
          <el-button link type="primary" @click="onExport(row)">导出</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <StrategyEditorDrawer
      v-model:visible="editorVisible"
      :mode="editorMode"
      :strategy="editingStrategy"
      :draft="importDraft"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.file-input {
  display: none;
}
</style>
