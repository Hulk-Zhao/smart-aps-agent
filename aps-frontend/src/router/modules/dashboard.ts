import type { RouterModule } from '../types'

export const dashboardModule: RouterModule = {
  key: 'dashboard',
  label: '总览',
  menuPath: '/',
  order: 10,
  menu: true,
  routes: [
    {
      path: '',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '总览' }
    }
  ]
}

export default dashboardModule
