<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => route.meta.title ?? '页面预留')
const description = computed(
  () => route.meta.description ?? '该页面已预留路由与菜单，将在后续前端任务中实现。'
)
</script>

<template>
  <div>
    <h2 class="page-title">{{ title }}</h2>
    <el-card shadow="never">
      <el-empty :description="description" />
    </el-card>
  </div>
</template>
