<script setup lang="ts">
import { reactive, ref } from 'vue'

import { submitSolveTask } from '@/api'
import type { SolveResult } from '@/types/api'

const form = reactive({
  taskNo: 'DEMO-001',
  engine: 'timefold',
  orderNos: ''
})

const submitting = ref(false)
const result = ref<SolveResult | null>(null)
const error = ref('')

async function onSubmit() {
  submitting.value = true
  error.value = ''
  result.value = null
  try {
    const orderNos = form.orderNos
      .split(',')
      .map((orderNo) => orderNo.trim())
      .filter((orderNo) => orderNo.length > 0)
    const response = await submitSolveTask({
      taskNo: form.taskNo,
      engine: form.engine,
      orderNos,
      parameters: {}
    })
    result.value = response.data
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '求解请求失败'
  } finally {
    submitting.value = false
  }
}
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
    <el-card v-if="result" shadow="never">
      <template #header>求解结果</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="任务号">{{ result.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ result.status }}</el-descriptions-item>
        <el-descriptions-item label="说明" :span="2">{{ result.message }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>
