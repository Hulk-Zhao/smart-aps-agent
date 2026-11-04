<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { createIdempotencyKey } from '@/api/http'
import { confirmOrder, freezeOrder, getOrder, listOrders, unfreezeOrder } from '@/api/orders'
import { DataTable, StatusTag } from '@/components/common'
import { getEnumOptions } from '@/constants/enums'
import type { Order, OrderStatus } from '@/types/orders'

import OrderDetailDrawer from './orders/OrderDetailDrawer.vue'
import OrderFormDialog from './orders/OrderFormDialog.vue'

interface OrderFilters {
  orderNo: string
  customerId: string
  materialCode: string
  status: OrderStatus | ''
}

const statusOptions = getEnumOptions('orderStatus')

const filters = reactive<OrderFilters>({
  orderNo: '',
  customerId: '',
  materialCode: '',
  status: ''
})
const dueDateRange = ref<[string, string] | null>(null)

const orders = ref<Order[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const formVisible = ref(false)
const editingOrder = ref<Order | null>(null)
const detailVisible = ref(false)
const detailOrder = ref<Order | null>(null)
const actingOrderNo = ref('')

async function loadOrders() {
  loading.value = true
  error.value = ''
  const [dueDateFrom, dueDateTo] = dueDateRange.value ?? []
  try {
    const result = await listOrders({
      page: page.value,
      size: size.value,
      sort: 'dueDate,asc',
      orderNo: filters.orderNo.trim() || undefined,
      customerId: filters.customerId.trim() || undefined,
      materialCode: filters.materialCode.trim() || undefined,
      status: filters.status || undefined,
      dueDateFrom: dueDateFrom || undefined,
      dueDateTo: dueDateTo || undefined
    })
    orders.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadOrders()
}

function resetFilters() {
  filters.orderNo = ''
  filters.customerId = ''
  filters.materialCode = ''
  filters.status = ''
  dueDateRange.value = null
  search()
}

function openCreate() {
  editingOrder.value = null
  formVisible.value = true
}

function openEdit(order: Order) {
  editingOrder.value = order
  detailVisible.value = false
  formVisible.value = true
}

function openDetail(order: Order) {
  detailOrder.value = order
  detailVisible.value = true
}

function onSaved(order: Order) {
  if (!editingOrder.value) {
    page.value = 1
  }
  if (detailOrder.value?.orderNo === order.orderNo) {
    detailOrder.value = order
  }
  void loadOrders()
}

/** 用详情接口回读最新行；回读失败时保留动作响应，避免列表状态滞后。 */
async function refreshRow(orderNo: string, fallback: Order) {
  let latest = fallback
  try {
    latest = await getOrder(orderNo)
  } catch {
    // 统一拦截器已提示；保留动作返回值
  }
  const index = orders.value.findIndex((item) => item.orderNo === orderNo)
  if (index >= 0) {
    orders.value[index] = latest
  }
  if (detailOrder.value?.orderNo === orderNo) {
    detailOrder.value = latest
  }
}

async function runAction(order: Order, action: () => Promise<Order>, successText: string) {
  actingOrderNo.value = order.orderNo
  try {
    const updated = await action()
    ElMessage.success(successText)
    await refreshRow(order.orderNo, updated)
  } catch {
    // 统一拦截器已展示后端 message（含 422 缺 BOM / 409 冲突）
  } finally {
    actingOrderNo.value = ''
  }
}

async function confirmRow(order: Order) {
  try {
    await ElMessageBox.confirm(
      `确认前请自查：产品「${order.materialCode}」存在，BOM 与工艺路线均为唯一 ACTIVE。` +
        `后端仍会按 C8 校验，失败将返回 42200 并展示具体原因。是否确认订单「${order.orderNo}」？`,
      '确认订单',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await runAction(
    order,
    () => confirmOrder(order.orderNo, createIdempotencyKey('order-confirm')),
    '订单已确认'
  )
}

async function freezeRow(order: Order) {
  try {
    await ElMessageBox.confirm(
      `冻结订单「${order.orderNo}」后，人工调整不可移动相关任务；可随时解冻。是否继续？`,
      '冻结订单',
      { type: 'warning', confirmButtonText: '冻结', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await runAction(
    order,
    () =>
      freezeOrder(
        order.orderNo,
        { freezeStatus: 'FROZEN', version: order.version },
        createIdempotencyKey('order-freeze')
      ),
    '订单已冻结'
  )
}

async function unfreezeRow(order: Order) {
  try {
    await ElMessageBox.confirm(
      `解冻订单「${order.orderNo}」后恢复人工调整权限。是否继续？`,
      '解冻订单',
      {
        type: 'warning',
        confirmButtonText: '解冻',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }
  await runAction(
    order,
    () => unfreezeOrder(order.orderNo, order.version, createIdempotencyKey('order-unfreeze')),
    '订单已解冻'
  )
}

onMounted(loadOrders)
</script>

<template>
  <div>
    <h2 class="page-title">订单管理</h2>

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="订单号">
          <el-input
            v-model="filters.orderNo"
            clearable
            placeholder="按订单号模糊查询"
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="客户">
          <el-input
            v-model="filters.customerId"
            clearable
            placeholder="客户 UUID"
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="物料">
          <el-input
            v-model="filters.materialCode"
            clearable
            placeholder="物料编码"
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 140px">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="交期">
          <el-date-picker
            v-model="dueDateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="orders"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="orderNo"
      empty-text="暂无订单数据"
      @change="loadOrders"
      @retry="loadOrders"
    >
      <template #toolbar>
        <el-button type="primary" @click="openCreate">新建订单</el-button>
      </template>

      <el-table-column prop="orderNo" label="订单号" min-width="150" />
      <el-table-column prop="materialCode" label="物料编码" min-width="150" />
      <el-table-column prop="qty" label="数量" width="90" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <StatusTag enum-key="orderStatus" :value="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="冻结状态" width="130">
        <template #default="{ row }">
          <StatusTag enum-key="freezeStatus" :value="row.freezeStatus" />
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          <StatusTag enum-key="orderPriority" :value="row.priority" />
        </template>
      </el-table-column>
      <el-table-column prop="dueDate" label="交期" width="110" />
      <el-table-column label="客户" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerId || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT'"
            link
            type="success"
            :loading="actingOrderNo === row.orderNo"
            @click="confirmRow(row)"
          >
            确认
          </el-button>
          <el-button
            v-if="row.freezeStatus === 'UNFROZEN'"
            link
            type="warning"
            :loading="actingOrderNo === row.orderNo"
            @click="freezeRow(row)"
          >
            冻结
          </el-button>
          <el-button
            v-else
            link
            type="success"
            :loading="actingOrderNo === row.orderNo"
            @click="unfreezeRow(row)"
          >
            解冻
          </el-button>
        </template>
      </el-table-column>
    </DataTable>

    <OrderFormDialog v-model="formVisible" :source="editingOrder" @saved="onSaved" />

    <OrderDetailDrawer
      v-model="detailVisible"
      :order="detailOrder"
      @edit="openEdit"
      @confirm="confirmRow"
      @freeze="freezeRow"
      @unfreeze="unfreezeRow"
    />
  </div>
</template>
