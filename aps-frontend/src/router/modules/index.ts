import type { RouterModule } from '../types'

import { dashboardModule } from './dashboard'
import { ganttModule } from './gantt'
import { masterDataModule } from './master-data'
import { mrpModule } from './mrp'
import { ordersModule } from './orders'
import { processModule } from './process'
import { solveModule } from './solve'
import { strategiesModule } from './strategies'

/**
 * 唯一新增路由的追加点：FE2/FE5/FE6 在此追加 import 与数组项，
 * 不要修改 src/router/index.ts。
 */
export const routerModules: RouterModule[] = [
  dashboardModule,
  masterDataModule,
  processModule,
  ordersModule,
  strategiesModule,
  mrpModule,
  solveModule,
  ganttModule
]

export const menuModules: RouterModule[] = [...routerModules]
  .filter((module) => module.menu !== false)
  .sort((left, right) => left.order - right.order)
