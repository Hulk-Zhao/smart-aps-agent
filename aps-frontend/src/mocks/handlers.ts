/**
 * 契约 Mock 处理器：按 OpenAPI 路径返回统一 Result 信封。
 * 仅用于前端并行开发；通过 VITE_ENABLE_MOCK=true 启用。
 */
import type { InternalAxiosRequestConfig } from 'axios'

import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { PageResult, Result } from '@/types/common'
import type { Bom, BomCreateRequest, BomTreeNode } from '@/types/boms'
import type { Material, MaterialCreateRequest } from '@/types/materials'
import type { MrpAnalysisParams, MrpAnalysisResult } from '@/types/mrp'
import type { Calendar, CalendarCreateRequest } from '@/types/calendars'
import type { Resource, ResourceCreateRequest } from '@/types/resources'
import type {
  DependType,
  Routing,
  RoutingCreateRequest,
  RoutingEdge,
  RoutingEdgePayload,
  RoutingGraphUpdateRequest,
  RoutingNode,
  RoutingNodePayload
} from '@/types/routings'
import type {
  Order,
  OrderCreateRequest,
  OrderFreezeRequest,
  OrderUpdateRequest,
  Sandbox,
  SandboxCreateRequest,
  Strategy,
  StrategyCreateRequest,
  StrategyDetail,
  StrategyRule,
  StrategyUpdateRequest
} from '@/types/orders'
import type { LoginRequest, User, UserCreateRequest, ConfigUpdateRequest } from '@/types/system'
import type { SolveSubmitRequest, SolveTaskDetail } from '@/types/solve'
import type { TaskAdjustRequest } from '@/types/gantt'

import {
  auditLogs,
  availableTime,
  boms,
  calendars,
  configs,
  ganttOrderRows,
  ganttResourceRows,
  ganttTasks,
  materials,
  mrpAnalysisFixture,
  operationTemplates,
  orders,
  resources,
  routings,
  sandboxes,
  solveTaskDetails,
  strategies,
  users
} from './data'

export interface MockReply {
  status: number
  body: Result<unknown>
}

function traceOf(config: InternalAxiosRequestConfig): string {
  const header = config.headers?.get?.('X-Trace-Id')
  if (typeof header === 'string' && header.length > 0) {
    return header
  }
  return `mock-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

function ok<T>(config: InternalAxiosRequestConfig, data: T, status = 200): MockReply {
  return {
    status,
    body: { code: 0, message: 'success', data, traceId: traceOf(config) }
  }
}

function fail(
  config: InternalAxiosRequestConfig,
  code: number,
  message: string,
  status: number
): MockReply {
  return {
    status,
    body: { code, message, data: null, traceId: traceOf(config) }
  }
}

function resolvePath(config: InternalAxiosRequestConfig): string {
  const raw = config.url ?? ''
  const withoutHost = raw.replace(/^https?:\/\/[^/]+/i, '')
  const withoutBase = withoutHost.replace(/^\/api\/v1/, '')
  const path = withoutBase.split('?')[0] || '/'
  return path.length > 1 ? path.replace(/\/+$/, '') : path
}

function readParams(config: InternalAxiosRequestConfig): Record<string, unknown> {
  return (config.params ?? {}) as Record<string, unknown>
}

function readBody<T>(config: InternalAxiosRequestConfig): T {
  const raw = config.data
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as T
    } catch {
      return {} as T
    }
  }
  if (
    raw &&
    typeof raw === 'object' &&
    !(typeof FormData !== 'undefined' && raw instanceof FormData)
  ) {
    return raw as T
  }
  return {} as T
}

function paginate<T>(items: T[], params: Record<string, unknown>): PageResult<T> {
  const page = Math.max(1, Number(params.page) || 1)
  const size = Math.min(Math.max(Number(params.size) || 20, 1), 200)
  const total = items.length
  return {
    items: items.slice((page - 1) * size, page * size),
    page,
    size,
    total,
    totalPages: Math.ceil(total / size)
  }
}

function listParam(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item))
  }
  if (typeof value === 'string' && value.length > 0) {
    return value.split(',')
  }
  return []
}

function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () =>
    Math.floor(Math.random() * 16).toString(16)
  )
}

function now(): string {
  return new Date().toISOString()
}

function nextNumericId(): number {
  return solveTaskDetails.reduce((max, item) => Math.max(max, item.id), 1000) + 1
}

const BOM_TREE_MAX_DEPTH = 5

function materialNameOf(code: string): string {
  return materials.find((item) => item.code === code)?.name ?? code
}

/**
 * 按父件逐层拼接 BOM 树；优先使用 ACTIVE 子 BOM，最多展开 5 层并阻止环引用。
 */
function buildBomTree(bomId: string): BomTreeNode | null {
  const rootBom = boms.find((item) => item.id === bomId)
  if (!rootBom) {
    return null
  }
  const visiting = new Set<string>()

  function build(bom: Bom, level: number, qtyPerUnit: number): BomTreeNode {
    visiting.add(bom.parentMaterialCode)
    const children: BomTreeNode[] = []
    if (level < BOM_TREE_MAX_DEPTH) {
      bom.items.forEach((item) => {
        if (visiting.has(item.materialCode)) {
          return
        }
        const childBom =
          boms.find(
            (candidate) =>
              candidate.parentMaterialCode === item.materialCode && candidate.status === 'ACTIVE'
          ) ?? boms.find((candidate) => candidate.parentMaterialCode === item.materialCode)
        if (childBom) {
          children.push(build(childBom, level + 1, item.qtyPerUnit))
          return
        }
        children.push({
          bomId: '',
          parentMaterialCode: item.materialCode,
          materialName: materialNameOf(item.materialCode),
          qtyPerUnit: item.qtyPerUnit,
          level: level + 1,
          children: []
        })
      })
    }
    visiting.delete(bom.parentMaterialCode)
    return {
      bomId: bom.id,
      parentMaterialCode: bom.parentMaterialCode,
      materialName: materialNameOf(bom.parentMaterialCode),
      qtyPerUnit,
      level,
      children
    }
  }

  return build(rootBom, 1, 1)
}

const DEPEND_TYPES: DependType[] = ['FS', 'SS', 'FF']

/** Mock 侧保存校验：复刻服务端按 seqNo 的环检测/唯一键/必填约束，避免前端错误被放行。 */
function validateRoutingPayload(
  nodes: RoutingNodePayload[],
  edges: RoutingEdgePayload[]
): string | null {
  if (nodes.length === 0) {
    return '工艺路线至少需要一个工序节点'
  }

  const seqNos = new Set<number>()
  for (const node of nodes) {
    if (!Number.isInteger(node.seqNo) || node.seqNo < 0) {
      return '工序 seqNo 必须为非负整数'
    }
    if (seqNos.has(node.seqNo)) {
      return `seqNo=${node.seqNo} 重复，请为每个工序设置唯一序号`
    }
    seqNos.add(node.seqNo)
  }

  const templateIds = new Set(operationTemplates.map((template) => template.id))
  const missingTemplate = nodes.find(
    (node) => !node.operationTemplateId || !templateIds.has(node.operationTemplateId)
  )
  if (missingTemplate) {
    return `工序「${missingTemplate.seqNo} · ${missingTemplate.operationName || missingTemplate.operationCode}」缺少有效工序模版`
  }

  const resourceIds = new Set(resources.map((resource) => resource.id))
  for (const node of nodes) {
    const primaryResourceMode = node.primaryResourceMode ?? 'INHERIT'
    if (!['INHERIT', 'FLOAT', 'FIXED'].includes(primaryResourceMode)) {
      return `工序「${node.seqNo} · ${node.operationName}」的主资源模式不合法`
    }
    if (primaryResourceMode === 'FIXED' && !node.primaryResourceId) {
      return `工序「${node.seqNo} · ${node.operationName}」主资源模式为 FIXED 但未选择资源`
    }
    if (
      primaryResourceMode === 'FIXED' &&
      node.primaryResourceId &&
      !resourceIds.has(node.primaryResourceId)
    ) {
      return `工序「${node.seqNo} · ${node.operationName}」的主资源不存在`
    }
    const secondaryResourceIds = node.secondaryResourceIds ?? []
    const missingSecondary = secondaryResourceIds.find((resourceId) => !resourceIds.has(resourceId))
    if (missingSecondary) {
      return `工序「${node.seqNo} · ${node.operationName}」的副资源 ${missingSecondary} 不存在`
    }
  }

  const edgeKeys = new Set<string>()
  for (const edge of edges) {
    if (!seqNos.has(edge.fromSeqNo) || !seqNos.has(edge.toSeqNo)) {
      return '依赖边引用了不存在的工序 seqNo'
    }
    if (edge.fromSeqNo === edge.toSeqNo) {
      return '不允许工序自依赖'
    }
    if (!DEPEND_TYPES.includes(edge.dependType) || edge.lagMinutes < 0) {
      return '依赖类型或滞后时间不合法'
    }
    const key = `${edge.fromSeqNo}->${edge.toSeqNo}:${edge.dependType}`
    if (edgeKeys.has(key)) {
      return '存在重复的单向依赖边（同一 fromSeqNo/toSeqNo 与依赖类型）'
    }
    edgeKeys.add(key)
  }

  if (nodes.length > 1) {
    const incoming = new Set(edges.map((edge) => edge.toSeqNo))
    const outgoing = new Set(edges.map((edge) => edge.fromSeqNo))
    const isolated = nodes.find((node) => !incoming.has(node.seqNo) && !outgoing.has(node.seqNo))
    if (isolated) {
      return `存在孤立工序「${isolated.seqNo} · ${isolated.operationName || isolated.operationCode}」`
    }
  }

  const indegree = new Map<number, number>()
  const adjacency = new Map<number, number[]>()
  nodes.forEach((node) => {
    indegree.set(node.seqNo, 0)
    adjacency.set(node.seqNo, [])
  })
  edges.forEach((edge) => {
    adjacency.get(edge.fromSeqNo)?.push(edge.toSeqNo)
    indegree.set(edge.toSeqNo, (indegree.get(edge.toSeqNo) ?? 0) + 1)
  })
  const queue = nodes
    .filter((node) => (indegree.get(node.seqNo) ?? 0) === 0)
    .map((node) => node.seqNo)
  let visited = 0
  while (queue.length > 0) {
    const current = queue.shift() as number
    visited += 1
    for (const next of adjacency.get(current) ?? []) {
      const remaining = (indegree.get(next) ?? 0) - 1
      indegree.set(next, remaining)
      if (remaining === 0) {
        queue.push(next)
      }
    }
  }
  if (visited !== nodes.length) {
    return '工艺路线存在循环依赖，请检查依赖边方向'
  }

  return null
}

function resourceCodeOf(resourceId: string | null): string | null {
  if (!resourceId) {
    return null
  }
  return resources.find((resource) => resource.id === resourceId)?.code ?? null
}

/** 契约节点（无 id/position）转 Mock 存储节点；按 seqNo 复用旧节点位置。 */
function toMockRoutingNodes(
  payloads: RoutingNodePayload[],
  previous: RoutingNode[] = []
): RoutingNode[] {
  return payloads.map((payload, index) => {
    const prior = previous.find((node) => node.seqNo === payload.seqNo)
    const stored: RoutingNode = {
      seqNo: payload.seqNo,
      operationTemplateId: payload.operationTemplateId,
      operationCode: payload.operationCode,
      operationName: payload.operationName,
      standardSeconds: payload.standardSeconds ?? 0,
      setupSeconds: payload.setupSeconds ?? 0,
      primaryResourceId: payload.primaryResourceId,
      primaryResourceMode: payload.primaryResourceMode ?? 'INHERIT',
      optional: payload.optional,
      id: prior?.id ?? `node-${payload.seqNo}-${index}`,
      primaryResourceCode: resourceCodeOf(payload.primaryResourceId),
      position: prior?.position ?? { x: 80 + index * 260, y: 80 }
    }
    // 缺键与 [] 必须区别存储：显式空数组才写入字段。
    if (payload.secondaryResourceIds !== undefined) {
      stored.secondaryResourceIds = [...payload.secondaryResourceIds]
    } else {
      delete stored.secondaryResourceIds
    }
    return stored
  })
}

/**
 * Mock 读出序列化：历史 fixture 未写入 primaryResourceMode，当时 id 即显式指定，
 * 故按 FIXED 回显（兼容旧数据，仅存在于 Mock；前端不得用 id 反推模式）。
 */
function serializeRoutingNode(node: RoutingNode): RoutingNode {
  return {
    ...node,
    primaryResourceMode: node.primaryResourceMode ?? (node.primaryResourceId ? 'FIXED' : 'INHERIT')
  }
}

function serializeRouting(routing: Routing): Routing {
  return { ...routing, nodes: routing.nodes.map(serializeRoutingNode) }
}

function toMockRoutingEdges(payloads: RoutingEdgePayload[]): RoutingEdge[] {
  return payloads.map((payload, index) => ({
    ...payload,
    id: `edge-${payload.fromSeqNo}-${payload.toSeqNo}-${payload.dependType}-${index}`
  }))
}

function mockToday(): string {
  const date = new Date()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function sortItems<T>(
  items: T[],
  params: Record<string, unknown>,
  accessors: Record<string, (item: T) => string | number>
): T[] {
  const sort = String(params.sort ?? '').trim()
  if (!sort) {
    return items
  }
  const [field, direction] = sort.split(',')
  const accessor = accessors[field]
  if (!accessor) {
    return items
  }
  const factor = direction === 'desc' ? -1 : 1
  return [...items].sort((left, right) => {
    const leftValue = accessor(left)
    const rightValue = accessor(right)
    if (leftValue === rightValue) {
      return 0
    }
    return leftValue > rightValue ? factor : -factor
  })
}

/** 资源保存校验：复刻 C2 产能口径与日历引用约束（42200）。 */
function validateResourcePayload(body: Partial<ResourceCreateRequest>): string | null {
  const mode = body.capacityMode ?? 'UNIT_PER_HOUR'
  const capacityValue = Number(body.capacityValue ?? 0)
  const efficiency = body.efficiency ?? 1
  if (efficiency < 0.5 || efficiency > 1.5) {
    return '效率系数需在 0.5–1.5 之间'
  }
  if (mode === 'UNIT_PER_HOUR' && capacityValue <= 0) {
    return '件/小时模式下 capacity_value 必须大于 0'
  }
  if (mode === 'BATCH_SECONDS') {
    if (capacityValue <= 0) {
      return '批次秒模式下 capacity_value（每批秒数）必须大于 0'
    }
    if (Number(body.batchCapacity ?? 0) <= 0) {
      return '批次秒模式下 batch_capacity 必须大于 0'
    }
  }
  if (body.calendarId && !calendars.some((item) => item.id === body.calendarId)) {
    return '日历引用不存在（calendar_id 无效）'
  }
  return null
}

function handleResourceRequest(
  config: InternalAxiosRequestConfig,
  method: string,
  path: string,
  params: Record<string, unknown>
): MockReply | null {
  if (method === 'GET' && path === '/resources') {
    const code = String(params.code ?? '').trim()
    const name = String(params.name ?? '').trim()
    const type = String(params.type ?? '').trim()
    const groupId = String(params.groupId ?? '').trim()
    const status = String(params.status ?? '').trim()
    const filtered = resources.filter((item) => {
      if (code && !item.code.toLowerCase().includes(code.toLowerCase())) {
        return false
      }
      if (name && !item.name.includes(name)) {
        return false
      }
      if (type && item.type !== type) {
        return false
      }
      if (groupId && item.groupId !== groupId) {
        return false
      }
      if (status && (item.status ?? 'ACTIVE') !== status) {
        return false
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      code: (item) => item.code,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'POST' && path === '/resources') {
    const body = readBody<Partial<ResourceCreateRequest>>(config)
    if (!body.code || !body.name) {
      return fail(config, 40000, '资源编码与名称不能为空', 400)
    }
    if (resources.some((item) => item.code === body.code)) {
      return fail(config, 40900, '资源编码已存在', 409)
    }
    const validation = validateResourcePayload(body)
    if (validation) {
      return fail(config, 42200, validation, 422)
    }
    const resource: Resource = {
      id: uuid(),
      code: body.code,
      name: body.name,
      type: body.type ?? 'PRIMARY',
      groupId: body.groupId ?? null,
      capacityMode: body.capacityMode ?? 'UNIT_PER_HOUR',
      capacityValue: body.capacityValue ?? 1,
      batchCapacity: body.batchCapacity ?? null,
      efficiency: body.efficiency ?? 1,
      calendarId: body.calendarId ?? null,
      setupSeconds: body.setupSeconds ?? 0,
      teardownSeconds: body.teardownSeconds ?? 0,
      maintenancePlan: {},
      industryAttrs: body.industryAttrs ?? {},
      status: 'ACTIVE',
      version: 1
    }
    resources.unshift(resource)
    return ok(config, resource)
  }

  const maintenanceMatch = path.match(/^\/resources\/([^/]+)\/maintenance$/)
  if (method === 'POST' && maintenanceMatch) {
    const code = decodeURIComponent(maintenanceMatch[1])
    const resource = resources.find((item) => item.code === code)
    if (!resource) {
      return fail(config, 40400, '资源不存在', 404)
    }
    resource.maintenancePlan = readBody<Record<string, unknown>>(config)
    resource.version += 1
    return ok(config, resource)
  }

  const resourceMatch = path.match(/^\/resources\/([^/]+)$/)
  if (resourceMatch) {
    const code = decodeURIComponent(resourceMatch[1])
    const resource = resources.find((item) => item.code === code)

    if (method === 'GET' && resourceMatch) {
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      return ok(config, resource)
    }

    if (method === 'PUT') {
      const body = readBody<Partial<ResourceCreateRequest> & { version?: number }>(config)
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      if (body.version !== undefined && body.version !== resource.version) {
        return fail(config, 40900, '资源已被他人修改，请刷新后重试', 409)
      }
      if (!body.name) {
        return fail(config, 40000, '资源名称不能为空', 400)
      }
      const validation = validateResourcePayload({ ...body, code: resource.code })
      if (validation) {
        return fail(config, 42200, validation, 422)
      }
      resource.name = body.name
      resource.type = body.type ?? resource.type
      resource.groupId = body.groupId ?? null
      resource.capacityMode = body.capacityMode ?? resource.capacityMode
      resource.capacityValue = body.capacityValue ?? resource.capacityValue
      resource.batchCapacity = body.batchCapacity ?? null
      resource.efficiency = body.efficiency ?? 1
      resource.calendarId = body.calendarId ?? null
      resource.setupSeconds = body.setupSeconds ?? 0
      resource.teardownSeconds = body.teardownSeconds ?? 0
      resource.industryAttrs = body.industryAttrs ?? resource.industryAttrs
      resource.version += 1
      return ok(config, resource)
    }

    if (method === 'DELETE') {
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      resource.status = 'INACTIVE'
      resource.version += 1
      return ok(config, null)
    }
  }

  return null
}

function validateCalendarPayload(body: Partial<CalendarCreateRequest>): string | null {
  if (!body.name || !body.calendarVersion) {
    return '日历名称与版本不能为空'
  }
  if (!body.effectiveFrom) {
    return '生效日期不能为空'
  }
  if (body.effectiveTo && body.effectiveTo < body.effectiveFrom) {
    return '失效日期不能早于生效日期'
  }
  const shifts = body.shifts ?? []
  if (shifts.length === 0) {
    return '至少需要一个班次'
  }
  for (const shift of shifts) {
    if (!shift.startTime || !shift.endTime) {
      return '班次起止时间不能为空'
    }
    if (shift.startTime === shift.endTime) {
      return '班次起止时间不能相同'
    }
    if (!Array.isArray(shift.daysOfWeek) || shift.daysOfWeek.length === 0) {
      return '班次至少选择一个工作日'
    }
    if (shift.daysOfWeek.some((day) => day < 1 || day > 7)) {
      return '工作日取值必须为 1-7'
    }
  }
  for (const holiday of body.holidays ?? []) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(holiday.date)) {
      return '例外日期格式应为 yyyy-MM-dd'
    }
    if (!holiday.name) {
      return '例外日期名称不能为空'
    }
    if (!['LEGAL', 'COMPANY', 'MAKEUP'].includes(holiday.kind)) {
      return '例外日期类型只能为 LEGAL / COMPANY / MAKEUP'
    }
  }
  return null
}

function handleCalendarRequest(
  config: InternalAxiosRequestConfig,
  method: string,
  path: string,
  params: Record<string, unknown>
): MockReply | null {
  if (method === 'GET' && path === '/calendars') {
    const name = String(params.name ?? '').trim()
    const type = String(params.type ?? '').trim()
    const filtered = calendars.filter((item) => {
      if (name && !item.name.includes(name)) {
        return false
      }
      if (type && item.type !== type) {
        return false
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      effectiveFrom: (item) => item.effectiveFrom,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'POST' && path === '/calendars') {
    const body = readBody<Partial<CalendarCreateRequest>>(config)
    const validation = validateCalendarPayload(body)
    if (validation) {
      const status = !body.name || !body.calendarVersion ? 400 : 422
      return fail(config, status === 400 ? 40000 : 42200, validation, status)
    }
    const calendar: Calendar = {
      id: uuid(),
      name: body.name as string,
      calendarVersion: body.calendarVersion as number,
      type: body.type ?? 'FACTORY',
      resourceId: body.resourceId ?? null,
      resourceGroupId: body.resourceGroupId ?? null,
      effectiveFrom: body.effectiveFrom as string,
      effectiveTo: body.effectiveTo ?? null,
      shifts: body.shifts ?? [],
      holidays: body.holidays ?? [],
      version: 1
    }
    calendars.unshift(calendar)
    return ok(config, calendar)
  }

  const availableMatch = path.match(/^\/calendars\/([^/]+)\/available$/)
  if (method === 'GET' && availableMatch) {
    return ok(config, availableTime)
  }

  const calendarMatch = path.match(/^\/calendars\/([^/]+)$/)
  if (calendarMatch) {
    const id = decodeURIComponent(calendarMatch[1])
    const calendar = calendars.find((item) => item.id === id)
    if (!calendar) {
      return fail(config, 40400, '日历不存在', 404)
    }

    if (method === 'GET') {
      return ok(config, calendar)
    }

    if (method === 'PUT') {
      // PRD：历史日历只读，服务端返回 403；前端编辑入口已提前拦截
      if (calendar.effectiveTo && calendar.effectiveTo < mockToday()) {
        return fail(config, 40300, '历史日历只读，请复制为新版本后修改', 403)
      }
      const body = readBody<Partial<CalendarCreateRequest> & { version?: number }>(config)
      if (body.version !== undefined && body.version !== calendar.version) {
        return fail(config, 40900, '日历已被他人修改，请刷新后重试', 409)
      }
      const validation = validateCalendarPayload(body)
      if (validation) {
        return fail(config, 42200, validation, 422)
      }
      calendar.name = body.name as string
      calendar.calendarVersion = body.calendarVersion as number
      calendar.type = body.type ?? calendar.type
      calendar.resourceId = body.resourceId ?? null
      calendar.resourceGroupId = body.resourceGroupId ?? null
      calendar.effectiveFrom = body.effectiveFrom as string
      calendar.effectiveTo = body.effectiveTo ?? null
      calendar.shifts = body.shifts ?? calendar.shifts
      calendar.holidays = body.holidays ?? []
      calendar.version += 1
      return ok(config, calendar)
    }
  }

  return null
}

const SOFT_WEIGHT_TOTAL = 100

/** 软约束权重合计：复刻 C19「权重和=100」口径，仅统计已启用的 SOFT 规则。 */
function sumEnabledSoftWeights(rules: StrategyRule[]): number {
  return rules
    .filter((rule) => rule.enabled && rule.type === 'SOFT')
    .reduce((total, rule) => total + (Number(rule.weight) || 0), 0)
}

/** 策略规则保存校验：规则代码唯一、至少一条启用 SOFT、权重和=100（42200）。 */
function validateStrategyRules(rules: StrategyRule[]): string | null {
  const codes = new Set<string>()
  for (const rule of rules) {
    if (!rule.code) {
      return '规则代码不能为空'
    }
    if (codes.has(rule.code)) {
      return `规则 ${rule.code} 重复，同一策略下规则代码必须唯一`
    }
    codes.add(rule.code)
    if (rule.type !== 'HARD' && rule.type !== 'SOFT') {
      return `规则 ${rule.code} 的 rule_type 必须为 HARD/SOFT`
    }
  }
  if (!rules.some((rule) => rule.enabled && rule.type === 'SOFT')) {
    return '至少需要一条启用的软约束规则'
  }
  const total = sumEnabledSoftWeights(rules)
  if (Math.abs(total - SOFT_WEIGHT_TOTAL) > 1e-6) {
    return `软约束权重之和必须为 ${SOFT_WEIGHT_TOTAL}，当前为 ${total}`
  }
  return null
}

const MOCK_SOLVE_RUN_MS = 6000

/**
 * Mock 异步求解：按 elapsed 推进进度，供 FE-05 轮询兜底联调。
 * 真实环境的事实源仍是 GET /solve-tasks/{id}，STOMP 只推送。
 */
function advanceMockSolveTask(detail: SolveTaskDetail): SolveTaskDetail {
  if (detail.status !== 'PENDING' && detail.status !== 'RUNNING') {
    return detail
  }
  const startedAt = detail.startedAt ? Date.parse(detail.startedAt) : Date.now()
  const elapsed = Math.max(0, Date.now() - startedAt)
  detail.elapsedMs = elapsed
  if (elapsed < 800) {
    detail.status = 'PENDING'
    detail.progress = 0
    detail.phase = 'ASSEMBLING'
    detail.bestScore = null
    return detail
  }
  if (elapsed < MOCK_SOLVE_RUN_MS) {
    const ratio = (elapsed - 800) / (MOCK_SOLVE_RUN_MS - 800)
    detail.status = 'RUNNING'
    detail.progress = Math.min(0.95, ratio * 0.95)
    detail.phase = elapsed < 2600 ? 'INITIAL' : 'LOCAL_SEARCH'
    detail.bestScore = `0hard/${Math.round(-400 + ratio * 355)}soft`
    return detail
  }
  detail.status = 'SOLVED'
  detail.progress = 1
  detail.phase = 'FINISHED'
  detail.bestScore = '0hard/-45soft'
  detail.scoreHard = 0
  detail.scoreSoft = -45
  detail.resultTaskId = detail.id
  detail.finishedAt = now()
  return detail
}

export function handleMockRequest(config: InternalAxiosRequestConfig): MockReply {
  const method = (config.method ?? 'get').toUpperCase()
  const path = resolvePath(config)
  const params = readParams(config)

  if (method === 'GET' && path === '/health') {
    return ok(config, { status: 'UP', service: 'aps-admin (mock)' })
  }

  if (method === 'POST' && path === '/auth/login') {
    const body = readBody<Partial<LoginRequest>>(config)
    if (!body.username) {
      return fail(config, 40000, '用户名不能为空', 400)
    }
    return ok(config, {
      accessToken: 'mock-access-token',
      tokenType: 'Bearer',
      expiresIn: 7200,
      userId: 'ffffffff-ffff-4fff-8fff-ffffffffffff'
    })
  }

  if (method === 'GET' && path === '/users') {
    return ok(config, paginate(users, params))
  }

  if (method === 'POST' && path === '/users') {
    const body = readBody<Partial<UserCreateRequest>>(config)
    if (!body.username) {
      return fail(config, 40000, '用户名不能为空', 400)
    }
    const user: User = {
      id: uuid(),
      username: body.username,
      displayName: body.displayName ?? null,
      email: body.email ?? null,
      phone: body.phone ?? null,
      status: 'ACTIVE',
      lastLoginAt: null,
      version: 1
    }
    users.unshift(user)
    return ok(config, user)
  }

  if (method === 'GET' && path === '/audit-logs') {
    return ok(config, paginate(auditLogs, params))
  }

  if (method === 'GET' && path === '/configs') {
    return ok(config, configs)
  }

  if (method === 'PUT' && path === '/configs') {
    const body = readBody<Partial<ConfigUpdateRequest>>(config)
    body.items?.forEach((item) => {
      const existing = configs.find((entry) => entry.key === item.key)
      if (existing) {
        existing.value = item.value
        existing.updatedAt = now()
      } else {
        configs.push({ key: item.key, value: item.value, description: null, updatedAt: now() })
      }
    })
    return ok(config, configs)
  }

  if (method === 'GET' && path === '/materials') {
    const type = params.type
    const codeKeyword = String(params.code ?? '')
      .trim()
      .toLowerCase()
    const nameKeyword = String(params.name ?? '')
      .trim()
      .toLowerCase()
    // 物料选择器会把同一关键字同时放进 code/name，此时按 OR 处理；独立筛选时按 AND。
    const sharedKeyword =
      codeKeyword && nameKeyword && codeKeyword === nameKeyword ? codeKeyword : ''
    const filtered = materials.filter((item) => {
      if (type && item.type !== type) {
        return false
      }
      if (sharedKeyword) {
        return (
          item.code.toLowerCase().includes(sharedKeyword) ||
          item.name.toLowerCase().includes(sharedKeyword)
        )
      }
      if (codeKeyword && !item.code.toLowerCase().includes(codeKeyword)) {
        return false
      }
      if (nameKeyword && !item.name.toLowerCase().includes(nameKeyword)) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/materials') {
    const body = readBody<Partial<MaterialCreateRequest>>(config)
    if (!body.code || !body.name) {
      return fail(config, 40000, '物料编码与名称不能为空', 400)
    }
    if (!body.type) {
      return fail(config, 40000, '物料类型不能为空', 400)
    }
    if (!body.unit) {
      return fail(config, 40000, '计量单位不能为空', 400)
    }
    if (materials.some((item) => item.code === body.code)) {
      return fail(config, 40900, '物料编码已存在', 409)
    }
    const material: Material = {
      code: body.code,
      name: body.name,
      type: body.type,
      spec: body.spec,
      unit: body.unit,
      categoryId: body.categoryId ?? null,
      safetyStock: body.safetyStock ?? 0,
      moq: body.moq ?? 0,
      leadTimeDays: body.leadTimeDays ?? 0,
      defaultSupplierId: body.defaultSupplierId ?? null,
      extAttrs: body.extAttrs ?? {},
      industryAttrs: body.industryAttrs ?? {},
      version: 1,
      createdAt: now(),
      updatedAt: now()
    }
    materials.unshift(material)
    return ok(config, material)
  }

  if (method === 'POST' && path === '/materials/import') {
    return ok(config, {
      successCount: 12,
      failureCount: 2,
      errors: [
        { row: 3, code: 'MAT-RAW-3003', message: '单位不能为空' },
        { row: 9, code: 'MAT-RAW-3001', message: '物料编码重复' }
      ]
    })
  }

  const materialDetail = path.match(/^\/materials\/([^/]+)$/)
  if (materialDetail) {
    const code = decodeURIComponent(materialDetail[1])
    const index = materials.findIndex((item) => item.code === code)
    if (index < 0) {
      return fail(config, 40400, '物料不存在', 404)
    }
    if (method === 'GET') {
      return ok(config, materials[index])
    }
    if (method === 'PUT') {
      const body = readBody<Partial<Material>>(config)
      if (body.version !== materials[index].version) {
        return fail(config, 40901, '物料已被修改，请刷新后重试', 409)
      }
      const updated: Material = {
        ...materials[index],
        ...body,
        code,
        version: materials[index].version + 1,
        updatedAt: now()
      }
      materials[index] = updated
      return ok(config, updated)
    }
    if (method === 'DELETE') {
      const referencedByBom = boms.some(
        (bom) =>
          bom.parentMaterialCode === code || bom.items.some((item) => item.materialCode === code)
      )
      const referencedByOrder = orders.some((order) => order.materialCode === code)
      if (referencedByBom || referencedByOrder) {
        return fail(config, 42200, '物料已被 BOM 或订单引用，无法停用', 422)
      }
      materials.splice(index, 1)
      return ok(config, null)
    }
  }

  if (method === 'GET' && path === '/boms') {
    const parentMaterialCode = String(params.parentMaterialCode ?? '')
    const status = String(params.status ?? '')
    const filtered = boms.filter((item) => {
      if (parentMaterialCode && item.parentMaterialCode !== parentMaterialCode) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/boms') {
    const body = readBody<Partial<BomCreateRequest>>(config)
    const items = Array.isArray(body.items) ? body.items : []
    if (!body.parentMaterialCode || !body.bomVersion || !body.effectiveDate) {
      return fail(config, 40000, '父件物料、版本与生效日期不能为空', 400)
    }
    if (items.length === 0) {
      return fail(config, 42200, 'BOM 明细至少需要一行', 422)
    }
    if (!materials.some((item) => item.code === body.parentMaterialCode)) {
      return fail(config, 42200, `父件物料 ${body.parentMaterialCode} 不存在`, 422)
    }
    const invalidItem = items.find((item) => {
      if (!item.materialCode || !materials.some((entry) => entry.code === item.materialCode)) {
        return true
      }
      return !(item.qtyPerUnit > 0)
    })
    if (invalidItem) {
      return fail(
        config,
        42200,
        `组件物料 ${invalidItem.materialCode ?? '（空）'} 不存在或单位用量不大于 0`,
        422
      )
    }
    if (
      boms.some(
        (item) =>
          item.parentMaterialCode === body.parentMaterialCode && item.bomVersion === body.bomVersion
      )
    ) {
      return fail(config, 40900, '同一父件下 BOM 版本已存在', 409)
    }
    const bom: Bom = {
      id: uuid(),
      parentMaterialCode: body.parentMaterialCode,
      bomVersion: body.bomVersion,
      effectiveDate: body.effectiveDate,
      expiryDate: body.expiryDate ?? null,
      status: 'DRAFT',
      isPanel: body.isPanel ?? false,
      panelSize: body.panelSize ?? null,
      items,
      version: 1
    }
    boms.unshift(bom)
    return ok(config, bom)
  }

  const bomTreeMatch = path.match(/^\/boms\/([^/]+)\/tree$/)
  if (method === 'GET' && bomTreeMatch) {
    const tree = buildBomTree(decodeURIComponent(bomTreeMatch[1]))
    if (!tree) {
      return fail(config, 40400, 'BOM 不存在', 404)
    }
    return ok(config, tree)
  }

  const bomActivateMatch = path.match(/^\/boms\/([^/]+)\/activate$/)
  if (method === 'POST' && bomActivateMatch) {
    const bom = boms.find((item) => item.id === decodeURIComponent(bomActivateMatch[1]))
    if (!bom) {
      return fail(config, 40400, 'BOM 不存在', 404)
    }
    // Mock 按 C8 收敛为单 ACTIVE；真实后端仍可能返回 409，前端已提前提示。
    boms.forEach((item) => {
      if (item.parentMaterialCode === bom.parentMaterialCode) {
        item.status = 'INACTIVE'
      }
    })
    bom.status = 'ACTIVE'
    bom.version += 1
    return ok(config, bom)
  }

  const resourceReply = handleResourceRequest(config, method, path, params)
  if (resourceReply) {
    return resourceReply
  }

  const calendarReply = handleCalendarRequest(config, method, path, params)
  if (calendarReply) {
    return calendarReply
  }

  if (method === 'GET' && path === '/operation-templates') {
    const code = String(params.code ?? '')
      .trim()
      .toLowerCase()
    const name = String(params.name ?? '')
      .trim()
      .toLowerCase()
    const resourceType = String(params.resourceType ?? '').trim()
    const filtered = operationTemplates.filter((template) => {
      const hasKeyword = Boolean(code || name)
      const keywordMatched =
        !hasKeyword ||
        (code ? template.code.toLowerCase().includes(code) : false) ||
        (name ? template.name.toLowerCase().includes(name) : false)
      if (!keywordMatched) {
        return false
      }
      if (resourceType) {
        const primary = resources.find((resource) => resource.id === template.primaryResourceId)
        if (!primary || primary.type !== resourceType) {
          return false
        }
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      code: (item) => item.code,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'GET' && path === '/routings') {
    const productCode = String(params.productCode ?? '').trim()
    const version = String(params.version ?? '')
      .trim()
      .toLowerCase()
    const status = String(params.status ?? '')
    const filtered = routings.filter((item) => {
      if (productCode && item.productCode !== productCode) {
        return false
      }
      if (version && item.routingVersion.toLowerCase() !== version) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/routings') {
    const body = readBody<Partial<RoutingCreateRequest>>(config)
    const productCode = body.productCode?.trim() ?? ''
    const routingVersion = body.routingVersion?.trim() ?? ''
    if (!productCode || !routingVersion) {
      return fail(config, 40000, '产品编码与版本不能为空', 400)
    }
    if (!body.effectiveDate) {
      return fail(config, 40000, '生效日期不能为空', 400)
    }
    if (!materials.some((item) => item.code === productCode)) {
      return fail(config, 42200, `产品物料 ${productCode} 不存在`, 422)
    }
    if (
      routings.some(
        (item) => item.productCode === productCode && item.routingVersion === routingVersion
      )
    ) {
      return fail(config, 40900, '同一产品下工艺路线版本已存在', 409)
    }
    const nodes = Array.isArray(body.nodes) ? body.nodes : []
    const edges = Array.isArray(body.edges) ? body.edges : []
    const error = validateRoutingPayload(nodes, edges)
    if (error) {
      return fail(config, 42200, error, 422)
    }
    const routing: Routing = {
      id: uuid(),
      productCode,
      productName: materialNameOf(productCode),
      routingVersion,
      effectiveDate: body.effectiveDate,
      expiryDate: body.expiryDate ?? null,
      status: 'DRAFT',
      nodes: toMockRoutingNodes(nodes),
      edges: toMockRoutingEdges(edges),
      version: 1,
      updatedAt: now()
    }
    routings.unshift(routing)
    return ok(config, routing)
  }

  const routingDetailMatch = path.match(/^\/routings\/([^/]+)$/)
  if (routingDetailMatch && method === 'GET') {
    const routingId = decodeURIComponent(routingDetailMatch[1])
    const routing = routings.find((item) => item.id === routingId)
    if (!routing) {
      return fail(config, 40400, '工艺路线不存在', 404)
    }
    return ok(config, serializeRouting(routing))
  }

  const routingGraphMatch = path.match(/^\/routings\/([^/]+)\/graph$/)
  if (routingGraphMatch) {
    const routingId = decodeURIComponent(routingGraphMatch[1])
    const routing = routings.find((item) => item.id === routingId)
    if (!routing) {
      return fail(config, 40400, '工艺路线不存在', 404)
    }
    if (method === 'GET') {
      return ok(config, { ...serializeRouting(routing), routingId: routing.id })
    }
    if (method === 'PUT') {
      if (routing.status !== 'DRAFT') {
        const reason = routing.status === 'ACTIVE' ? '生效版本只读' : '历史版本只读'
        return fail(config, 42200, `${reason}，请新建版本后再编辑`, 422)
      }
      const body = readBody<Partial<RoutingGraphUpdateRequest>>(config)
      if (typeof body.version !== 'number') {
        return fail(config, 40000, 'version 为必填乐观锁字段', 400)
      }
      if (body.version !== routing.version) {
        return fail(config, 40901, '工艺路线已被修改，请刷新后重试', 409)
      }
      if (!Array.isArray(body.nodes) || !Array.isArray(body.edges)) {
        return fail(config, 40000, 'nodes 与 edges 必填，保存为整体替换', 400)
      }
      const nodes = body.nodes
      const edges = body.edges
      const error = validateRoutingPayload(nodes, edges)
      if (error) {
        return fail(config, 42200, error, 422)
      }
      routing.nodes = toMockRoutingNodes(nodes, routing.nodes)
      routing.edges = toMockRoutingEdges(edges)
      routing.version += 1
      routing.updatedAt = now()
      return ok(config, { ...serializeRouting(routing), routingId: routing.id })
    }
  }

  if (method === 'POST' && path === '/mrp/analysis') {
    const body = readBody<Partial<MrpAnalysisParams>>(config)
    const blocking = body.blocking === true
    const orderNos = Array.isArray(body.orderNos) ? body.orderNos : []
    const orders = orderNos.length
      ? mrpAnalysisFixture.orders.filter((order) => orderNos.includes(order.orderNo))
      : mrpAnalysisFixture.orders
    const includeSuggestions = body.includeSuggestions !== false
    const shortages = includeSuggestions
      ? mrpAnalysisFixture.shortages
      : mrpAnalysisFixture.shortages.map((shortage) => ({
          ...shortage,
          suggestedPurchaseDate: null,
          suggestedQty: null,
          supplierCode: null
        }))
    const result: MrpAnalysisResult = {
      ...mrpAnalysisFixture,
      sandboxId: body.sandboxId ?? DEFAULT_SANDBOX_ID,
      analyzedAt: now(),
      blocking,
      blocked: blocking && mrpAnalysisFixture.status !== 'FULL',
      orderCount: orders.length,
      orders,
      shortages
    }
    return ok(config, result)
  }

  if (method === 'GET' && path === '/orders') {
    const orderNoKeyword = String(params.orderNo ?? '')
      .trim()
      .toLowerCase()
    const materialKeyword = String(params.materialCode ?? '')
      .trim()
      .toLowerCase()
    const customerKeyword = String(params.customerId ?? '')
      .trim()
      .toLowerCase()
    const status = String(params.status ?? '')
    const dueDateFrom = String(params.dueDateFrom ?? '')
    const dueDateTo = String(params.dueDateTo ?? '')
    const filtered = orders.filter((item) => {
      if (orderNoKeyword && !item.orderNo.toLowerCase().includes(orderNoKeyword)) {
        return false
      }
      if (materialKeyword && !item.materialCode.toLowerCase().includes(materialKeyword)) {
        return false
      }
      if (
        customerKeyword &&
        !String(item.customerId ?? '')
          .toLowerCase()
          .includes(customerKeyword)
      ) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      if (dueDateFrom && item.dueDate < dueDateFrom) {
        return false
      }
      if (dueDateTo && item.dueDate > dueDateTo) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/orders') {
    const body = readBody<Partial<OrderCreateRequest>>(config)
    if (!body.orderNo || !body.materialCode) {
      return fail(config, 40000, '订单号与物料编码不能为空', 400)
    }
    if (!body.dueDate) {
      return fail(config, 40000, '交期不能为空', 400)
    }
    if (!(Number(body.qty) > 0)) {
      return fail(config, 40000, '订单数量必须大于 0', 400)
    }
    if (orders.some((item) => item.orderNo === body.orderNo)) {
      return fail(config, 40900, '订单号已存在，请更换后重试', 409)
    }
    if (!materials.some((item) => item.code === body.materialCode)) {
      return fail(config, 42200, `产品 ${body.materialCode} 不存在`, 422)
    }
    const order: Order = {
      orderNo: body.orderNo,
      materialCode: body.materialCode,
      qty: body.qty ?? 0,
      status: 'DRAFT',
      freezeStatus: 'UNFROZEN',
      dueDate: body.dueDate ?? now().slice(0, 10),
      priority: body.priority ?? 'NORMAL',
      source: body.source ?? 'MANUAL',
      customerId: body.customerId ?? null,
      routingId: body.routingId ?? null,
      remark: body.remark ?? null,
      extAttrs: body.extAttrs ?? {},
      version: 1,
      createdAt: now(),
      updatedAt: now()
    }
    orders.unshift(order)
    return ok(config, order)
  }

  if (method === 'POST' && path === '/orders/atp') {
    return ok(config, {
      feasible: true,
      earliestDate: '2026-11-12',
      leadTimeDays: 8,
      bottleneckResourceCode: 'RES-AGE-01'
    })
  }

  const confirmMatch = path.match(/^\/orders\/([^/]+)\/confirm$/)
  if (method === 'PUT' && confirmMatch) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(confirmMatch[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    if (order.status !== 'DRAFT') {
      return fail(config, 40901, '仅草稿订单可确认', 409)
    }
    // C8：产品存在、BOM 唯一 ACTIVE、工艺路线存在，命中失败一律 42200 并直出 message。
    if (!(order.qty > 0) || !order.dueDate) {
      return fail(config, 40000, '订单数量必须大于 0 且交期不能为空', 400)
    }
    const product = materials.find((item) => item.code === order.materialCode)
    if (!product) {
      return fail(config, 42200, `产品 ${order.materialCode} 不存在，无法确认订单`, 422)
    }
    const activeBoms = boms.filter(
      (item) => item.parentMaterialCode === order.materialCode && item.status === 'ACTIVE'
    )
    if (activeBoms.length === 0) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 缺少唯一 ACTIVE 的 BOM，无法确认订单`,
        422
      )
    }
    if (activeBoms.length > 1) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 存在 ${activeBoms.length} 条 ACTIVE BOM，数据不一致`,
        422
      )
    }
    const routing = order.routingId
      ? routings.find((item) => item.id === order.routingId)
      : routings.find((item) => item.productCode === order.materialCode)
    if (!routing) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 缺少唯一 ACTIVE 的工艺路线，无法确认订单`,
        422
      )
    }
    order.status = 'CONFIRMED'
    order.version += 1
    order.updatedAt = now()
    return ok(config, order)
  }

  const freezeMatch = path.match(/^\/orders\/([^/]+)\/freeze$/)
  if (method === 'PUT' && freezeMatch) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(freezeMatch[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    const body = readBody<Partial<OrderFreezeRequest>>(config)
    if (body.version !== order.version) {
      return fail(config, 40901, '订单已被修改，请刷新后重试', 409)
    }
    order.freezeStatus = body.freezeStatus ?? 'UNFROZEN'
    order.version += 1
    order.updatedAt = now()
    return ok(config, order)
  }

  const orderDetail = path.match(/^\/orders\/([^/]+)$/)
  if (method === 'GET' && orderDetail) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(orderDetail[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    return ok(config, order)
  }

  if (method === 'PUT' && orderDetail) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(orderDetail[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    if (order.status !== 'DRAFT') {
      return fail(config, 40901, '仅草稿订单可编辑', 409)
    }
    const body = readBody<Partial<OrderUpdateRequest>>(config)
    if (body.version !== order.version) {
      return fail(config, 40901, '订单已被修改，请刷新后重试', 409)
    }
    if (!body.materialCode || !body.dueDate) {
      return fail(config, 40000, '物料编码与交期不能为空', 400)
    }
    if (!(Number(body.qty) > 0)) {
      return fail(config, 40000, '订单数量必须大于 0', 400)
    }
    if (!materials.some((item) => item.code === body.materialCode)) {
      return fail(config, 42200, `产品 ${body.materialCode} 不存在`, 422)
    }
    Object.assign(order, {
      materialCode: body.materialCode,
      qty: body.qty,
      dueDate: body.dueDate,
      priority: body.priority ?? order.priority,
      customerId: body.customerId ?? null,
      routingId: body.routingId ?? null,
      remark: body.remark ?? null,
      extAttrs: body.extAttrs ?? order.extAttrs,
      version: order.version + 1,
      updatedAt: now()
    })
    return ok(config, order)
  }

  if (method === 'GET' && path === '/strategies') {
    return ok(config, paginate(strategies, params))
  }

  if (method === 'POST' && path === '/strategies') {
    const body = readBody<Partial<StrategyCreateRequest>>(config)
    if (!body.name) {
      return fail(config, 40000, '策略名称不能为空', 400)
    }
    if (!body.effectiveFrom) {
      return fail(config, 40000, '生效日期不能为空', 400)
    }
    if (body.effectiveTo && body.effectiveFrom && body.effectiveTo < body.effectiveFrom) {
      return fail(config, 42200, '失效日期不能早于生效日期', 422)
    }
    const rules = Array.isArray(body.rules) ? body.rules : []
    if (Array.isArray(body.rules)) {
      const ruleError = validateStrategyRules(body.rules)
      if (ruleError) {
        return fail(config, 42200, ruleError, 422)
      }
    }
    const strategy: Strategy = {
      id: uuid(),
      name: body.name,
      direction: body.direction ?? 'FORWARD',
      strategyVersion: body.strategyVersion ?? 1,
      effectiveFrom: body.effectiveFrom,
      effectiveTo: body.effectiveTo ?? null,
      initialRules: body.initialRules ?? {},
      optimizationRules: body.optimizationRules ?? {},
      applyCondition: body.applyCondition ?? {},
      rules,
      version: 1
    }
    strategies.unshift(strategy)
    return ok(config, strategy)
  }

  const cloneMatch = path.match(/^\/strategies\/([^/]+)\/clone$/)
  if (method === 'POST' && cloneMatch) {
    const strategy = strategies.find((item) => item.id === decodeURIComponent(cloneMatch[1]))
    if (!strategy) {
      return fail(config, 40400, '策略不存在', 404)
    }
    const cloned: Strategy = {
      ...strategy,
      id: uuid(),
      name: `${strategy.name}（副本）`,
      strategyVersion: strategy.strategyVersion + 1,
      initialRules: { ...(strategy.initialRules ?? {}) },
      optimizationRules: { ...(strategy.optimizationRules ?? {}) },
      applyCondition: { ...(strategy.applyCondition ?? {}) },
      rules: (strategy.rules ?? []).map((rule) => ({ ...rule, id: undefined })),
      version: 1
    }
    strategies.unshift(cloned)
    return ok(config, cloned)
  }

  const strategyDetailMatch = path.match(/^\/strategies\/([^/]+)$/)
  if (strategyDetailMatch) {
    const strategyId = decodeURIComponent(strategyDetailMatch[1])
    const strategy = strategies.find((item) => item.id === strategyId)
    if (!strategy) {
      return fail(config, 40400, '策略不存在', 404)
    }
    if (method === 'GET') {
      const detail: StrategyDetail = { ...strategy, rules: strategy.rules ?? [] }
      return ok(config, detail)
    }
    if (method === 'PUT') {
      const body = readBody<Partial<StrategyUpdateRequest>>(config)
      if (typeof body.version !== 'number') {
        return fail(config, 40000, 'version 为必填乐观锁字段', 400)
      }
      if (body.version !== strategy.version) {
        return fail(config, 40901, '策略已被修改，请刷新后重试', 409)
      }
      if (!body.name) {
        return fail(config, 40000, '策略名称不能为空', 400)
      }
      if (!body.effectiveFrom) {
        return fail(config, 40000, '生效日期不能为空', 400)
      }
      if (body.effectiveTo && body.effectiveTo < body.effectiveFrom) {
        return fail(config, 42200, '失效日期不能早于生效日期', 422)
      }
      if (!Array.isArray(body.rules)) {
        return fail(config, 40000, 'rules 必填，保存为整体替换', 400)
      }
      const ruleError = validateStrategyRules(body.rules)
      if (ruleError) {
        return fail(config, 42200, ruleError, 422)
      }
      strategy.name = body.name
      strategy.direction = body.direction ?? strategy.direction
      strategy.strategyVersion = body.strategyVersion ?? strategy.strategyVersion
      strategy.effectiveFrom = body.effectiveFrom
      strategy.effectiveTo = body.effectiveTo ?? null
      strategy.initialRules = body.initialRules ?? strategy.initialRules
      strategy.optimizationRules = body.optimizationRules ?? strategy.optimizationRules
      strategy.applyCondition = body.applyCondition ?? strategy.applyCondition
      strategy.rules = body.rules
      strategy.version += 1
      const detail: StrategyDetail = { ...strategy, rules: strategy.rules }
      return ok(config, detail)
    }
  }

  if (method === 'GET' && path === '/sandboxes') {
    return ok(config, paginate(sandboxes, params))
  }

  if (method === 'POST' && path === '/sandboxes') {
    const body = readBody<Partial<SandboxCreateRequest>>(config)
    if (!body.name || !body.type) {
      return fail(config, 40000, '场景名称与类型不能为空', 400)
    }
    const sandbox: Sandbox = {
      id: uuid(),
      name: body.name,
      type: body.type,
      parentSandboxId: body.parentSandboxId ?? null,
      expiresAt: body.expiresAt ?? null,
      snapshot: body.snapshot ?? {},
      version: 1,
      createdAt: now()
    }
    sandboxes.unshift(sandbox)
    return ok(config, sandbox)
  }

  const applySandboxMatch = path.match(/^\/sandboxes\/([^/]+)\/apply$/)
  if (method === 'POST' && applySandboxMatch) {
    const sandbox = sandboxes.find((item) => item.id === decodeURIComponent(applySandboxMatch[1]))
    if (!sandbox) {
      return fail(config, 40400, '场景不存在', 404)
    }
    sandbox.type = 'BASELINE'
    sandbox.version += 1
    return ok(config, sandbox)
  }

  const compareSandboxMatch = path.match(/^\/sandboxes\/([^/]+)\/compare$/)
  if (method === 'GET' && compareSandboxMatch) {
    return ok(config, {
      sandboxId: decodeURIComponent(compareSandboxMatch[1]),
      targetId: String(params.targetId ?? ''),
      dueDateDeltaMinutes: -120,
      utilizationDelta: 3.5,
      setupCountDelta: -2,
      changedTasks: [
        { taskId: 2101, orderNo: 'SO-20261101-001', resourceCode: 'RES-SMT-01' },
        { taskId: 2102, orderNo: 'SO-20261101-001', resourceCode: 'RES-ASSY-01' }
      ]
    })
  }

  if (method === 'POST' && path === '/solve-tasks') {
    const body = readBody<Partial<SolveSubmitRequest>>(config)
    const taskNo = body.taskNo || `SOLVE-${Date.now()}`
    const detail: SolveTaskDetail = {
      id: nextNumericId(),
      taskNo,
      sandboxId: body.sandboxId ?? DEFAULT_SANDBOX_ID,
      strategyId: body.strategyId ?? null,
      engine: body.engine ?? 'timefold',
      status: 'PENDING',
      scoreHard: null,
      scoreSoft: null,
      progress: 0,
      phase: 'ASSEMBLING',
      bestScore: null,
      elapsedMs: 0,
      resultTaskId: null,
      startedAt: now(),
      finishedAt: null,
      metrics: { stepCount: 0, durationMs: 0 },
      errorMessage: null,
      createdAt: now()
    }
    solveTaskDetails.unshift(detail)
    // C20：异步求解返回 SolveTaskDetail，进度经 STOMP/轮询查询
    return ok(config, detail)
  }

  const solveTaskMatch = path.match(/^\/solve-tasks\/([^/]+)$/)
  if (method === 'GET' && solveTaskMatch) {
    const id = Number(solveTaskMatch[1])
    const detail = solveTaskDetails.find((item) => item.id === id)
    if (!detail) {
      return fail(config, 40400, '求解任务不存在', 404)
    }
    return ok(config, advanceMockSolveTask(detail))
  }

  const cancelSolveMatch = path.match(/^\/solve-tasks\/([^/]+)\/cancel$/)
  if (method === 'POST' && cancelSolveMatch) {
    const id = Number(cancelSolveMatch[1])
    const detail = solveTaskDetails.find((item) => item.id === id)
    if (!detail) {
      return fail(config, 40400, '求解任务不存在', 404)
    }
    if (['SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT'].includes(detail.status)) {
      return fail(config, 40901, '终态求解任务不可取消', 409)
    }
    detail.status = 'CANCELLED'
    return ok(config, detail)
  }

  if (path.startsWith('/agent/')) {
    return fail(config, 50100, '接口尚未实现（Mock 占位）', 501)
  }

  if (method === 'GET' && path === '/gantt/resource') {
    const codes = listParam(params.resourceCodes)
    const rows = codes.length
      ? ganttResourceRows.filter((row) => codes.includes(row.resourceCode))
      : ganttResourceRows
    return ok(config, {
      sandboxId: params.sandboxId || DEFAULT_SANDBOX_ID,
      from: params.from ?? '',
      to: params.to ?? '',
      resources: rows
    })
  }

  if (method === 'GET' && path === '/gantt/order') {
    const orderNos = listParam(params.orderNos)
    const rows = orderNos.length
      ? ganttOrderRows.filter((row) => orderNos.includes(row.orderNo))
      : ganttOrderRows
    return ok(config, {
      sandboxId: params.sandboxId || DEFAULT_SANDBOX_ID,
      from: params.from ?? '',
      to: params.to ?? '',
      orders: rows
    })
  }

  const adjustTaskMatch = path.match(/^\/tasks\/([^/]+)\/adjust$/)
  if (method === 'POST' && adjustTaskMatch) {
    const task = ganttTasks.find((item) => item.id === Number(adjustTaskMatch[1]))
    if (!task) {
      return fail(config, 40400, '排产任务不存在', 404)
    }
    const body = readBody<Partial<TaskAdjustRequest>>(config)
    if (body.version !== task.version) {
      return fail(config, 40901, '资源状态冲突，请刷新后重试', 409)
    }
    if (body.resourceCode !== undefined) {
      task.resourceCode = body.resourceCode
    }
    if (body.startTime) {
      task.startTime = body.startTime
    }
    if (body.endTime) {
      task.endTime = body.endTime
    }
    if (body.frozen !== undefined) {
      task.status = body.frozen ? 'FROZEN' : 'PLANNED'
    }
    task.version += 1
    return ok(config, task)
  }

  const planActionMatch = path.match(/^\/plans\/([^/]+)\/(publish|rollback)$/)
  if (method === 'POST' && planActionMatch) {
    return ok(config, {
      planId: decodeURIComponent(planActionMatch[1]),
      version: planActionMatch[2] === 'publish' ? 2 : 1,
      publishedAt: now(),
      affectedTaskCount: ganttTasks.length
    })
  }

  return fail(config, 40400, `Mock 未匹配到接口：${method} ${path}`, 404)
}
