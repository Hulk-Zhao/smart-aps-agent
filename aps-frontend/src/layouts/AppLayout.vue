<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { menuModules } from '@/router/modules'
import { useTraceStore } from '@/stores/trace'

import AppBreadcrumb from './components/AppBreadcrumb.vue'

const route = useRoute()
const traceStore = useTraceStore()

const activeMenu = computed(() => route.path)
const traceText = computed(() =>
  traceStore.latestTraceId ? traceStore.latestTraceId.slice(0, 12) : '暂无请求'
)

async function copyTraceId() {
  if (!traceStore.latestTraceId) {
    ElMessage.info('当前还没有请求链路 ID')
    return
  }
  try {
    await globalThis.navigator.clipboard.writeText(traceStore.latestTraceId)
    ElMessage.success('traceId 已复制')
  } catch {
    ElMessage.warning(`复制失败，traceId：${traceStore.latestTraceId}`)
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">衡策 APS</div>
      <el-menu :default-active="activeMenu" :default-openeds="['master-data']" router class="menu">
        <template v-for="module in menuModules" :key="module.key">
          <el-sub-menu v-if="module.menuItems?.length" :index="module.key">
            <template #title>{{ module.label }}</template>
            <el-menu-item v-for="item in module.menuItems" :key="item.path" :index="item.path">
              {{ item.label }}
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="module.menuPath">{{ module.label }}</el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <AppBreadcrumb />
        <div class="trace">
          <span class="trace-label">traceId</span>
          <el-tag size="small" type="info" effect="plain">{{ traceText }}</el-tag>
          <el-button link type="primary" @click="copyTraceId">复制</el-button>
        </div>
      </el-header>
      <el-main>
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.aside {
  background: #1f2d3d;
}

.brand {
  height: 60px;
  line-height: 60px;
  color: #ffffff;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
  letter-spacing: 2px;
}

.menu {
  border-right: none;
  background: #1f2d3d;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: #c0c4cc;
}

.menu :deep(.el-menu-item.is-active) {
  color: #ffffff;
  background: #324157;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.trace {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trace-label {
  color: #909399;
  font-size: 12px;
}
</style>
