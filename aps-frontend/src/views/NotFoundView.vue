<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="页面不存在" sub-title="请检查地址或返回总览">
    <template #extra>
      <el-button type="primary" @click="router.push('/')">返回总览</el-button>
    </template>
  </el-result>
</template>
