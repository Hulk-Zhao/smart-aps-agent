<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">衡策 APS</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/">总览</el-menu-item>
        <el-menu-item index="/orders">生产订单</el-menu-item>
        <el-menu-item index="/solve-tasks">排产求解</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>智能排产 · MVP 工程基座</span>
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

.menu :deep(.el-menu-item) {
  color: #c0c4cc;
}

.menu :deep(.el-menu-item.is-active) {
  color: #ffffff;
  background: #324157;
}

.header {
  display: flex;
  align-items: center;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}
</style>
