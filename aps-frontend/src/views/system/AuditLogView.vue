<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'

import { listAuditLogs } from '@/api/system'
import { DataTable } from '@/components/common'
import { AUDIT_ACTIONS, AUDIT_RESOURCE_TYPES, auditActionLabel, auditResourceLabel } from '@/constants/audit'
import type { AuditLog, AuditLogQuery } from '@/types/system'

const rows = ref<AuditLog[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')

const filters = reactive({
  action: '',
  resourceType: '',
  resourceId: '',
  userId: '',
  from: '',
  to: ''
})

function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

function prettyJson(value: Record<string, unknown> | null | undefined): string {
  if (value === null || value === undefined) {
    return '—'
  }
  return JSON.stringify(value, null, 2)
}

async function loadAuditLogs() {
  loading.value = true
  error.value = ''
  try {
    const query: AuditLogQuery = {
      page: page.value,
      size: size.value,
      action: filters.action || undefined,
      resourceType: filters.resourceType || undefined,
      resourceId: filters.resourceId.trim() || undefined,
      userId: filters.userId.trim() || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined
    }
    if (query.from && query.to && Date.parse(query.from) >= Date.parse(query.to)) {
      error.value = '开始时间必须早于结束时间（区间为 [from, to)）'
      rows.value = []
      total.value = 0
      return
    }
    const result = await listAuditLogs(query)
    rows.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size
  } catch (exception) {
    rows.value = []
    total.value = 0
    error.value = exception instanceof Error ? exception.message : '审计日志加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadAuditLogs()
}

function resetFilters() {
  filters.action = ''
  filters.resourceType = ''
  filters.resourceId = ''
  filters.userId = ''
  filters.from = ''
  filters.to = ''
  search()
}

onMounted(() => {
  void loadAuditLogs()
})
</script>

<template>
  <div>
    <h2 class="page-title">审计日志</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="id 为 int64：前端以字符串承载并只做展示，不参与数值运算"
      description="过滤条件为 AND 组合，时间区间固定 [from, to)；action 取 16 个闭集值，resourceType 取 7 个闭集值。默认展示最新在前（created_at DESC, id DESC）。"
    />

    <el-card shadow="never" class="section">
      <template #header>筛选</template>
      <div class="filter-grid">
        <div class="filter-field">
          <label class="field-label" for="audit-action">action</label>
          <el-select id="audit-action" v-model="filters.action" clearable placeholder="全部 action" style="width: 100%">
            <el-option
              v-for="option in AUDIT_ACTIONS"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </div>
        <div class="filter-field">
          <label class="field-label" for="audit-resource-type">resourceType</label>
          <el-select
            id="audit-resource-type"
            v-model="filters.resourceType"
            clearable
            placeholder="全部 resourceType"
            style="width: 100%"
          >
            <el-option
              v-for="option in AUDIT_RESOURCE_TYPES"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </div>
        <div class="filter-field">
          <label class="field-label" for="audit-resource-id">resourceId</label>
          <el-input id="audit-resource-id" v-model="filters.resourceId" clearable placeholder="精确匹配" @keyup.enter="search" />
        </div>
        <div class="filter-field">
          <label class="field-label" for="audit-user-id">userId</label>
          <el-input id="audit-user-id" v-model="filters.userId" clearable placeholder="精确匹配" @keyup.enter="search" />
        </div>
        <div class="filter-field">
          <label class="field-label" for="audit-from">from（含）</label>
          <el-date-picker
            id="audit-from"
            v-model="filters.from"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="起始时间"
            style="width: 100%"
          />
        </div>
        <div class="filter-field">
          <label class="field-label" for="audit-to">to（不含）</label>
          <el-date-picker
            id="audit-to"
            v-model="filters.to"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="结束时间"
            style="width: 100%"
          />
        </div>
      </div>
      <div class="filter-actions">
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </el-card>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="rows"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="id"
      empty-text="当前筛选下暂无审计记录"
      @change="loadAuditLogs"
      @retry="loadAuditLogs"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail-panel">
            <div class="detail-item">
              <p class="detail-title">traceId</p>
              <p class="detail-text">{{ row.traceId }}</p>
            </div>
            <div class="detail-item">
              <p class="detail-title">beforeValue</p>
              <pre class="detail-json">{{ prettyJson(row.beforeValue) }}</pre>
            </div>
            <div class="detail-item">
              <p class="detail-title">afterValue</p>
              <pre class="detail-json">{{ prettyJson(row.afterValue) }}</pre>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="id (int64)" width="120">
        <template #default="{ row }">{{ String(row.id) }}</template>
      </el-table-column>
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="action" min-width="190">
        <template #default="{ row }">
          <el-tooltip :content="row.action" placement="top">
            <el-tag size="small">{{ auditActionLabel(row.action) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="resourceType" width="130">
        <template #default="{ row }">{{ auditResourceLabel(row.resourceType) }}</template>
      </el-table-column>
      <el-table-column prop="resourceId" label="resourceId" min-width="230" show-overflow-tooltip />
      <el-table-column prop="userId" label="userId" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">{{ row.userId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="clientIp" label="clientIp" width="130">
        <template #default="{ row }">{{ row.clientIp || '—' }}</template>
      </el-table-column>
    </DataTable>
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

.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
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

.filter-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.detail-panel {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
  padding: var(--space-3) var(--space-6);
}

.detail-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-xs);
  color: var(--text-2);
}

.detail-text {
  margin: 0;
  color: var(--text);
  word-break: break-all;
}

.detail-json {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  border: var(--border-width) solid var(--border);
  color: var(--text);
  font-size: var(--font-xs);
  line-height: 1.6;
}
</style>
