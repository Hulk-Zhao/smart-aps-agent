import RouteGroup from '@/layouts/RouteGroup.vue'
import BomListView from '@/views/master-data/boms/BomListView.vue'
import CalendarListView from '@/views/master-data/calendars/CalendarListView.vue'
import MaterialListView from '@/views/master-data/materials/MaterialListView.vue'
import ResourceListView from '@/views/master-data/resources/ResourceListView.vue'

import type { RouterModule } from '../types'

export const masterDataModule: RouterModule = {
  key: 'master-data',
  label: '基础数据',
  menuPath: '/master-data/materials',
  order: 20,
  menu: true,
  menuItems: [
    { path: '/master-data/materials', label: '物料管理' },
    { path: '/master-data/boms', label: 'BOM 管理' },
    { path: '/master-data/resources', label: '资源管理' },
    { path: '/master-data/calendars', label: '日历管理' }
  ],
  routes: [
    {
      path: 'master-data',
      name: 'master-data',
      component: RouteGroup,
      redirect: '/master-data/materials',
      meta: { title: '基础数据' },
      children: [
        {
          path: 'materials',
          name: 'master-data-materials',
          component: MaterialListView,
          meta: { title: '物料管理' }
        },
        {
          path: 'boms',
          name: 'master-data-boms',
          component: BomListView,
          meta: { title: 'BOM 管理' }
        },
        {
          path: 'resources',
          name: 'master-data-resources',
          component: ResourceListView,
          meta: { title: '资源管理' }
        },
        {
          path: 'calendars',
          name: 'master-data-calendars',
          component: CalendarListView,
          meta: { title: '日历管理' }
        }
      ]
    }
  ]
}

export default masterDataModule
