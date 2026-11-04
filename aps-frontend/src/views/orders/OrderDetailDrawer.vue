<script setup lang="ts">
import { computed } from 'vue'

import { StatusTag } from '@/components/common'
import type { Order } from '@/types/orders'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    order?: Order | null
  }>(),
  {
    order: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  edit: [order: Order]
  confirm: [order: Order]
  freeze: [order: Order]
  unfreeze: [order: Order]
}>()

const editable = computed(() => props.order?.status === 'DRAFT')
const frozen = computed(() => props.order?.freezeStatus !== 'UNFROZEN')

const extAttrsText = computed(() =>
  props.order?.extAttrs && Object.keys(props.order.extAttrs).length > 0
    ? JSON.stringify(props.order.extAttrs, null, 2)
    : '—'
)
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="订单详情"
    size="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="order">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号" :span="2">{{ order.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="产品物料">{{ order.materialCode }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ order.qty }}</el-descriptions-item>
        <el-descriptions-item label="交期">{{ order.dueDate }}</el-descriptions-item>
        <el-descriptions-item label="优先级">
          <StatusTag enum-key="orderPriority" :value="order.priority" />
        </el-descriptions-item>
        <el-descriptions-item label="客户 ID">{{ order.customerId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源">
          <StatusTag enum-key="orderSource" :value="order.source" />
        </el-descriptions-item>
        <el-descriptions-item label="工艺路线">{{ order.routingId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="版本号">{{ order.version }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{
          order.remark || '—'
        }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-title">状态</div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单状态">
          <StatusTag enum-key="orderStatus" :value="order.status" />
        </el-descriptions-item>
        <el-descriptions-item label="冻结状态">
          <StatusTag enum-key="freezeStatus" :value="order.freezeStatus" />
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ order.createdAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ order.updatedAt || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        class="history-tip"
        type="info"
        :closable="false"
        show-icon
        title="契约未提供订单状态流转历史端点，此处仅展示当前状态与关键时间。"
      />

      <div class="section-title">扩展属性 ext_attrs</div>
      <pre class="json-block">{{ extAttrsText }}</pre>

      <div class="drawer-actions">
        <el-button v-if="editable" @click="emit('edit', order)">编辑</el-button>
        <el-button v-if="editable" type="primary" plain @click="emit('confirm', order)">
          确认订单
        </el-button>
        <el-button v-if="!frozen" type="warning" plain @click="emit('freeze', order)">
          冻结
        </el-button>
        <el-button v-else type="success" plain @click="emit('unfreeze', order)">解冻</el-button>
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      </div>
    </template>
    <el-empty v-else description="未选择订单" />
  </el-drawer>
</template>

<style scoped>
.section-title {
  margin: var(--space-5) 0 var(--space-2);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text);
}

.history-tip {
  margin-top: var(--space-3);
}

.json-block {
  margin: 0 0 var(--space-4);
  padding: var(--space-3);
  max-height: 220px;
  overflow: auto;
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  color: var(--text-2);
  font-size: var(--font-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-6);
}
</style>
