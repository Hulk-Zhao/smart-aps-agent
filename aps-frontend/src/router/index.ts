import { createRouter, createWebHistory } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import NotFoundView from '@/views/NotFoundView.vue'

import { routerModules } from './modules'

/**
 * 根路由冻结：页面不得直接修改本文件。
 * 新增业务模块请到 src/router/modules/ 新建模块文件，并在 modules/index.ts 注册。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: AppLayout,
      children: [
        ...routerModules.flatMap((module) => module.routes),
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: NotFoundView,
          meta: { title: '页面不存在', hidden: true }
        }
      ]
    }
  ]
})

export default router
