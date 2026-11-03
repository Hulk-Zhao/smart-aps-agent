<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchOrders } from '@/api'
import type { ProductionOrder } from '@/types/api'

const orders = ref<ProductionOrder[]>([])
const loading = ref(false)
const error = ref('')

async function loadOrders() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchOrders()
    orders.value = response.data
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadOrders)
</script>

<template>
  <div>
    <h2 class="page-title">生产订单</h2>
    <el-alert v-if="error" class="page-card" type="error" :closable="false" :title="error" />
    <el-card shadow="never">
      <el-table v-loading="loading" :data="orders" empty-text="暂无订单数据">
        <el-table-column prop="orderNo" label="订单号" min-width="140" />
        <el-table-column prop="materialCode" label="物料编码" min-width="140" />
        <el-table-column prop="qty" label="数量" width="100" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="freezeStatus" label="冻结状态" width="140" />
        <el-table-column prop="dueDate" label="交期" min-width="180" />
      </el-table>
    </el-card>
  </div>
</template>
