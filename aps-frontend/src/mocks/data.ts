/**
 * 契约 Mock 数据：字段严格按 docs/api/openapi/*.yaml 生成，
 * 枚举取值按 docs/db/schema.md 4.1 节 CHECK 取值。
 */
import type { AvailableTime, Bom, BomTreeNode, Material } from '@/types/materials'
import type { MrpAnalysisResult } from '@/types/mrp'
import type { Calendar } from '@/types/calendars'
import type { Resource } from '@/types/resources'
import type { OperationTemplate, Routing } from '@/types/routings'
import type { Order, Sandbox, Strategy } from '@/types/orders'
import type { SolveTaskDetail } from '@/types/solve'
import type { GanttOrderRow, GanttResourceRow, GanttTask } from '@/types/gantt'
import type { AuditLog, PermissionView, RoleView, SystemConfig, User } from '@/types/system'

const NOW = '2026-11-04T08:00:00Z'

const ID = {
  categoryElectrical: '11111111-1111-4111-8111-111111111111',
  supplierCell: '22222222-2222-4222-8222-222222222222',
  customerA: '33333333-3333-4333-8333-333333333333',
  resourceGroup: '44444444-4444-4444-8444-444444444444',
  calendar: '55555555-5555-4555-8555-555555555555',
  bom1: '66666666-6666-4666-8666-666666666666',
  bom2: '77777777-7777-4777-8777-777777777777',
  routing1: '88888888-8888-4888-8888-888888888888',
  routing2: '99999999-9999-4999-8999-999999999999',
  routing3: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  routing4: 'abababab-abab-4bab-8bab-abababababab',
  sandboxBaseline: '00000000-0000-0000-0000-000000000401',
  sandboxWhatIf: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  sandboxArchived: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  strategy1: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  strategy2: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  user1: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
  plan: '12121212-1212-4212-8212-121212121212'
} as const

/** C14：资源接口返回 id，Mock 使用稳定 UUID，避免每次刷新变化。 */
const RESOURCE_ID = {
  smt: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  age: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  assy: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  qc: '14141414-1414-4414-8414-141414141414',
  op: '15151515-1515-4515-8515-151515151515',
  tech: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
  mill: '13131313-1313-4313-8313-131313131313'
} as const

/** C15：工序模版 UUID；节点通过 operationTemplateId 引用。 */
const TEMPLATE_ID = {
  smt: '1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a',
  assy: '2b2b2b2b-2b2b-4b2b-8b2b-2b2b2b2b2b2b',
  age: '3c3c3c3c-3c3c-4c3c-8c3c-3c3c3c3c3c3c',
  qc: '4d4d4d4d-4d4d-4d4d-8d4d-4d4d4d4d4d4d'
} as const

export const materials: Material[] = [
  {
    code: 'MAT-FG-001',
    name: '储能电芯模组 48V/15Ah',
    type: 'FINISHED',
    spec: '48V 15Ah 16S1P',
    unit: '套',
    categoryId: ID.categoryElectrical,
    safetyStock: 50,
    moq: 10,
    leadTimeDays: 7,
    defaultSupplierId: null,
    extAttrs: { brand: 'HENGCE', tags: ['rohs', 'reach'] },
    industryAttrs: { cell_model: 'IFR32140', nominal_capacity_ah: 15, nominal_voltage_v: 3.2 },
    version: 2,
    createdAt: '2026-10-01T02:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'MAT-SF-001',
    name: '电池包半成品 16S1P',
    type: 'SEMI',
    spec: '16S1P 电池包',
    unit: '个',
    categoryId: ID.categoryElectrical,
    safetyStock: 80,
    moq: 20,
    leadTimeDays: 3,
    defaultSupplierId: null,
    extAttrs: { production_line: 'PACK-A' },
    industryAttrs: { grading_rule: { capacity_tolerance_pct: 0.5, resistance_max_mohm: 3 } },
    version: 1,
    createdAt: '2026-10-02T02:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'MAT-RM-101',
    name: '磷酸铁锂电芯 IFR32140',
    type: 'RAW',
    spec: '3.2V 15Ah',
    unit: '只',
    categoryId: ID.categoryElectrical,
    safetyStock: 5000,
    moq: 1000,
    leadTimeDays: 14,
    defaultSupplierId: ID.supplierCell,
    extAttrs: { customs_code: '8507.60' },
    industryAttrs: { nominal_capacity_ah: 15, nominal_voltage_v: 3.2 },
    version: 3,
    createdAt: '2026-09-20T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'MAT-RM-102',
    name: 'LFP 正极片卷料',
    type: 'RAW',
    spec: 'LFP 涂布卷料',
    unit: '米',
    categoryId: ID.categoryElectrical,
    safetyStock: 1200,
    moq: 200,
    leadTimeDays: 5,
    defaultSupplierId: null,
    version: 1,
    createdAt: '2026-09-21T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'MAT-AX-101',
    name: '绝缘胶带',
    type: 'AUX',
    spec: '10mm × 20m',
    unit: '卷',
    categoryId: null,
    safetyStock: 300,
    moq: 50,
    leadTimeDays: 2,
    defaultSupplierId: null,
    version: 1,
    createdAt: '2026-09-22T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'MAT-PK-101',
    name: '出口包装箱',
    type: 'PACK',
    spec: '600 × 400 × 350mm',
    unit: '个',
    categoryId: null,
    safetyStock: 200,
    moq: 20,
    leadTimeDays: 1,
    defaultSupplierId: null,
    version: 1,
    createdAt: '2026-09-23T03:00:00Z',
    updatedAt: NOW
  }
]

export const boms: Bom[] = [
  {
    id: ID.bom1,
    parentMaterialCode: 'MAT-FG-001',
    bomVersion: 'V1.0',
    effectiveDate: '2026-10-01',
    expiryDate: null,
    status: 'ACTIVE',
    isPanel: false,
    panelSize: null,
    items: [
      {
        materialCode: 'MAT-SF-001',
        qtyPerUnit: 1,
        scrapRate: 0.01,
        position: 'P1',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'MAT-AX-101',
        qtyPerUnit: 0.5,
        scrapRate: 0.02,
        position: 'P2',
        substitutes: ['MAT-AX-102'],
        isPhantom: false
      },
      {
        materialCode: 'MAT-PK-101',
        qtyPerUnit: 1,
        scrapRate: 0,
        position: 'P3',
        substitutes: [],
        isPhantom: false
      }
    ],
    version: 1
  },
  {
    id: ID.bom2,
    parentMaterialCode: 'MAT-SF-001',
    bomVersion: 'V2.0',
    effectiveDate: '2026-11-10',
    expiryDate: null,
    status: 'DRAFT',
    isPanel: false,
    panelSize: null,
    items: [
      {
        materialCode: 'MAT-RM-101',
        qtyPerUnit: 16,
        scrapRate: 0.005,
        position: 'CELL-01',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'MAT-RM-102',
        qtyPerUnit: 3.2,
        scrapRate: 0.01,
        position: 'ELECT-01',
        substitutes: [],
        isPhantom: false
      }
    ],
    version: 2
  }
]

export const bomTree: BomTreeNode = {
  bomId: ID.bom1,
  parentMaterialCode: 'MAT-FG-001',
  materialName: '储能电芯模组 48V/15Ah',
  qtyPerUnit: 1,
  level: 1,
  children: [
    {
      bomId: ID.bom2,
      parentMaterialCode: 'MAT-SF-001',
      materialName: '电池包半成品 16S1P',
      qtyPerUnit: 1,
      level: 2,
      children: [
        {
          bomId: ID.bom2,
          parentMaterialCode: 'MAT-RM-101',
          materialName: '磷酸铁锂电芯 IFR32140',
          qtyPerUnit: 16,
          level: 3,
          children: []
        }
      ]
    }
  ]
}

export const resources: Resource[] = [
  {
    id: RESOURCE_ID.smt,
    code: 'RES-SMT-01',
    name: 'SMT 贴片机 1 号',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 6,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {
      periodic: [{ cycle: 'WEEKLY', duration_minutes: 120 }],
      next_due_at: '2026-11-10T00:00:00Z',
      note: '每周三保养'
    },
    industryAttrs: { feeder_slots: 120, supported_packages: ['0402', '0603', 'QFN'] },
    version: 1
  },
  {
    id: RESOURCE_ID.age,
    code: 'RES-AGE-01',
    name: '储能老化柜 1 号',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'BATCH_SECONDS',
    capacityValue: 600,
    batchCapacity: 20,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: { aging_cabinet_capacity: 20, aging_hours: 24 },
    version: 1
  },
  {
    id: RESOURCE_ID.assy,
    code: 'RES-ASSY-01',
    name: '电池包总装线 1 号',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 0,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    version: 1
  },
  {
    id: RESOURCE_ID.qc,
    code: 'RES-QC-01',
    name: '质检工位 1 号',
    type: 'SECONDARY',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 0,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    version: 1
  },
  {
    id: RESOURCE_ID.op,
    code: 'RES-OP-01',
    name: '装配操作工 1 名',
    type: 'HUMAN',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 0,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    version: 1
  },
  {
    id: RESOURCE_ID.tech,
    code: 'RES-TECH-101',
    name: '工艺技术员组',
    type: 'HUMAN',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 30,
    batchCapacity: 1,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    version: 1
  },
  {
    // 已停用资源：用于验证状态筛选与停用交互（status 为前端兼容字段，契约缺口）
    id: RESOURCE_ID.mill,
    code: 'RES-MILL-101',
    name: '普铣 101 号（已停用）',
    type: 'TOOL',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 0,
    batchCapacity: null,
    efficiency: 1,
    calendarId: null,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    status: 'INACTIVE',
    version: 2
  }
]

export const calendars: Calendar[] = [
  {
    id: ID.calendar,
    name: '全厂 7×24 日历',
    calendarVersion: 1,
    type: 'FACTORY',
    resourceId: null,
    resourceGroupId: null,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    shifts: [{ startTime: '00:00', endTime: '24:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] }],
    holidays: [
      { date: '2026-10-01', name: '国庆节', kind: 'LEGAL' },
      { date: '2026-10-10', name: '调休上班', kind: 'MAKEUP' }
    ],
    version: 1
  },
  {
    id: '55555555-5555-4555-8555-555555555556',
    name: 'SMT 两班制',
    calendarVersion: 2,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2026-11-01',
    effectiveTo: null,
    shifts: [
      { startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5] },
      { startTime: '20:00', endTime: '06:00', daysOfWeek: [1, 2, 3, 4, 5] }
    ],
    holidays: [],
    version: 1
  },
  {
    // 历史日历：effectiveTo 早于今天，Mock 更新返回 403，前端禁用编辑
    id: '55555555-5555-4555-8555-555555555557',
    name: '总装线三班制',
    calendarVersion: 1,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2025-01-01',
    effectiveTo: '2025-12-31',
    shifts: [
      { startTime: '07:00', endTime: '15:00', daysOfWeek: [1, 2, 3, 4, 5] },
      { startTime: '15:00', endTime: '23:00', daysOfWeek: [1, 2, 3, 4, 5] },
      { startTime: '23:00', endTime: '07:00', daysOfWeek: [1, 2, 3, 4, 5] }
    ],
    holidays: [{ date: '2025-10-01', name: '国庆节', kind: 'LEGAL' }],
    version: 1
  },
  {
    // 待生效日历：校验未来版本编辑与生效状态标签
    id: '55555555-5555-4555-8555-555555555558',
    name: 'SMT 旺季两班制',
    calendarVersion: 3,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2026-12-01',
    effectiveTo: null,
    shifts: [{ startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5, 6] }],
    holidays: [],
    version: 1
  }
]

export const availableTime: AvailableTime = {
  items: [
    { startTime: '2026-11-05T00:00:00Z', endTime: '2026-11-05T12:00:00Z', minutes: 720 },
    { startTime: '2026-11-06T00:00:00Z', endTime: '2026-11-06T12:00:00Z', minutes: 720 }
  ]
}

export const operationTemplates: OperationTemplate[] = [
  {
    id: TEMPLATE_ID.smt,
    code: 'OP-SMT-01',
    name: 'SMT 贴片',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.smt,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 60,
    setupSeconds: 30,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.assy,
    code: 'OP-ASSY-01',
    name: '电池包总装',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.assy,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 30,
    setupSeconds: 10,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.age,
    code: 'OP-AGE-01',
    name: '老化烘烤',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.age,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 600,
    setupSeconds: 30,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.qc,
    code: 'OP-QC-01',
    name: '出厂检验',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.assy,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 20,
    setupSeconds: 10,
    teardownSeconds: 0,
    version: 1
  }
]

/**
 * 工艺路线 DAG Mock：
 * - MAT-FG-001 同时存在 INACTIVE（V0.9）/ ACTIVE（V1.0）/ DRAFT（V1.1），覆盖版本切换与只读态；
 * - V1.1 草稿内置环、缺模版、seq_no 冲突、重复单向边、孤立节点，用于前端就地校验演示；
 * - MAT-SF-001 V2.0 为正常草稿链。
 */
export const routings: Routing[] = [
  {
    id: ID.routing2,
    productCode: 'MAT-FG-001',
    productName: '储能电芯模组 48V/15Ah',
    routingVersion: 'V1.1',
    effectiveDate: '2026-11-10',
    expiryDate: null,
    status: 'DRAFT',
    nodes: [
      {
        id: 'n-smt-11',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-11',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '电池包总装',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      },
      {
        id: 'n-age-11',
        seqNo: 30,
        operationTemplateId: null,
        operationCode: 'OP-AGE-01',
        operationName: '老化烘烤（待补模版）',
        standardSeconds: 600,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.age,
        primaryResourceCode: 'RES-AGE-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 680, y: 80 }
      },
      {
        id: 'n-qc-11',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.qc,
        operationCode: 'OP-QC-01',
        operationName: '出厂检验（seq 冲突）',
        standardSeconds: 20,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [RESOURCE_ID.qc],
        optional: false,
        position: { x: 380, y: 260 }
      },
      {
        id: 'n-pack-11',
        seqNo: 40,
        operationTemplateId: TEMPLATE_ID.qc,
        operationCode: 'OP-QC-01',
        operationName: '出厂检验（孤立节点）',
        standardSeconds: 20,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [],
        optional: true,
        position: { x: 680, y: 260 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-11',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-assy-age-11',
        fromSeqNo: 20,
        toSeqNo: 30,
        dependType: 'FS',
        lagMinutes: 15
      },
      {
        id: 'e-age-smt-11',
        fromSeqNo: 30,
        toSeqNo: 10,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-smt-assy-11-dup',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      }
    ],
    version: 1,
    updatedAt: NOW
  },
  {
    id: ID.routing1,
    productCode: 'MAT-FG-001',
    productName: '储能电芯模组 48V/15Ah',
    routingVersion: 'V1.0',
    effectiveDate: '2026-10-01',
    expiryDate: null,
    status: 'ACTIVE',
    nodes: [
      {
        id: 'n-smt-10',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-10',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '电池包总装',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      },
      {
        id: 'n-age-10',
        seqNo: 30,
        operationTemplateId: TEMPLATE_ID.age,
        operationCode: 'OP-AGE-01',
        operationName: '老化烘烤',
        standardSeconds: 600,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.age,
        primaryResourceCode: 'RES-AGE-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 680, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-10',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-assy-age-10',
        fromSeqNo: 20,
        toSeqNo: 30,
        dependType: 'FS',
        lagMinutes: 30
      }
    ],
    version: 3,
    updatedAt: NOW
  },
  {
    id: ID.routing4,
    productCode: 'MAT-FG-001',
    productName: '储能电芯模组 48V/15Ah',
    routingVersion: 'V0.9',
    effectiveDate: '2026-08-01',
    expiryDate: '2026-10-01',
    status: 'INACTIVE',
    nodes: [
      {
        id: 'n-smt-09',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-09',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '电池包总装',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 380, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-09',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'SS',
        lagMinutes: 10
      }
    ],
    version: 2,
    updatedAt: '2026-09-20T08:00:00Z'
  },
  {
    id: ID.routing3,
    productCode: 'MAT-SF-001',
    productName: '电池包半成品 16S1P',
    routingVersion: 'V2.0',
    effectiveDate: '2026-11-10',
    expiryDate: null,
    status: 'DRAFT',
    nodes: [
      {
        id: 'n-smt-21',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-21',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '电池包总装',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-ASSY-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-21',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      }
    ],
    version: 1,
    updatedAt: NOW
  }
]

export const orders: Order[] = [
  {
    orderNo: 'SO-20261101-0001',
    materialCode: 'MAT-FG-001',
    qty: 120,
    status: 'SCHEDULED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-12',
    priority: 'URGENT',
    source: 'ERP_SYNC',
    customerId: ID.customerA,
    routingId: ID.routing1,
    remark: '储能客户首批交付',
    extAttrs: { sales_region: '华东' },
    version: 2,
    createdAt: '2026-11-01T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261101-0002',
    materialCode: 'MAT-SF-001',
    qty: 300,
    status: 'CONFIRMED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-15',
    priority: 'HIGH',
    source: 'MANUAL',
    customerId: null,
    routingId: ID.routing2,
    remark: null,
    extAttrs: {},
    version: 1,
    createdAt: '2026-11-01T02:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261102-0003',
    materialCode: 'MAT-FG-001',
    qty: 80,
    status: 'RELEASED',
    freezeStatus: 'FROZEN',
    dueDate: '2026-11-08',
    priority: 'URGENT',
    source: 'ERP_SYNC',
    customerId: ID.customerA,
    routingId: ID.routing1,
    remark: '冻结时间窗内不可移动',
    extAttrs: {},
    version: 3,
    createdAt: '2026-11-02T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261102-0004',
    materialCode: 'MAT-SF-001',
    qty: 500,
    status: 'IN_PROGRESS',
    freezeStatus: 'LOCKED_RESOURCE',
    dueDate: '2026-11-06',
    priority: 'NORMAL',
    source: 'ERP_SYNC',
    customerId: null,
    routingId: ID.routing2,
    remark: null,
    extAttrs: {},
    version: 2,
    createdAt: '2026-11-02T03:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261103-0005',
    materialCode: 'MAT-FG-001',
    qty: 60,
    status: 'DRAFT',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-20',
    priority: 'LOW',
    source: 'AGENT',
    customerId: null,
    routingId: ID.routing1,
    remark: 'Agent 推荐补单',
    extAttrs: {},
    version: 1,
    createdAt: '2026-11-03T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261028-0006',
    materialCode: 'MAT-SF-001',
    qty: 200,
    status: 'COMPLETED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-03',
    priority: 'NORMAL',
    source: 'MANUAL',
    customerId: null,
    routingId: ID.routing2,
    remark: null,
    extAttrs: {},
    version: 4,
    createdAt: '2026-10-28T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261029-0007',
    materialCode: 'MAT-FG-001',
    qty: 30,
    status: 'CANCELLED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-10',
    priority: 'LOW',
    source: 'MANUAL',
    customerId: null,
    routingId: ID.routing1,
    remark: '客户取消',
    extAttrs: {},
    version: 2,
    createdAt: '2026-10-29T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261104-0008',
    materialCode: 'MAT-FG-001',
    qty: 7,
    status: 'DRAFT',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-14',
    priority: 'NORMAL',
    source: 'MANUAL',
    customerId: null,
    routingId: null,
    remark: '小批量模组订单',
    extAttrs: {},
    version: 1,
    createdAt: NOW,
    updatedAt: NOW
  },
  {
    orderNo: 'SO-20261104-0011',
    materialCode: 'MAT-FG-001',
    qty: 150,
    status: 'CONFIRMED',
    freezeStatus: 'LOCKED_TIME_WINDOW',
    dueDate: '2026-11-18',
    priority: 'HIGH',
    source: 'ERP_SYNC',
    customerId: ID.customerA,
    routingId: ID.routing1,
    remark: null,
    extAttrs: {},
    version: 1,
    createdAt: NOW,
    updatedAt: NOW
  },
  {
    // 确认失败样例：MAT-SF-001 只有 DRAFT BOM，触发 C8 的 42200。
    orderNo: 'SO-20261104-0010',
    materialCode: 'MAT-SF-001',
    qty: 100,
    status: 'DRAFT',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-25',
    priority: 'NORMAL',
    source: 'MANUAL',
    customerId: ID.customerA,
    routingId: ID.routing2,
    remark: '待补生效 BOM 后确认',
    extAttrs: {},
    version: 1,
    createdAt: NOW,
    updatedAt: NOW
  }
]

export const strategies: Strategy[] = [
  {
    id: ID.strategy1,
    name: '交期优先策略',
    direction: 'BACKWARD',
    strategyVersion: 1,
    effectiveFrom: '2026-10-01',
    effectiveTo: null,
    initialRules: {
      sequence: ['DUE_DATE', 'PRIORITY'],
      dimensions: [
        { dimension: 'DUE_DATE', direction: 'ASC', weight: 40 },
        { dimension: 'PRIORITY', direction: 'DESC', weight: 30 },
        { dimension: 'CUSTOMER_LEVEL', direction: 'DESC', weight: 15 },
        { dimension: 'INVENTORY_STATUS', direction: 'ASC', weight: 10 },
        { dimension: 'RELEASE_TIME', direction: 'ASC', weight: 5 }
      ],
      defaultSetupMinutes: 30
    },
    optimizationRules: {
      weights: { tardiness: 10, setup: 3, utilization: 1 },
      rules: [{ code: 'MIN_SETUP_TIME', type: 'SOFT', weight: 15 }]
    },
    applyCondition: { productLines: ['PACK-A'], orderSources: ['ERP_SYNC'] },
    rules: [
      {
        code: 'MIN_TARDINESS',
        name: '最小超期',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 1
      },
      {
        code: 'EDD',
        name: '交期优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 2
      },
      {
        code: 'SPT',
        name: '短任务优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 3
      },
      {
        code: 'SAME_PRODUCT_CONTINUOUS',
        name: '同品连续生产',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 4
      },
      {
        code: 'MIN_SETUP_TIME',
        name: '最小换型时长',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 5
      },
      {
        code: 'BALANCE_LOAD',
        name: '均衡负载率',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 6
      },
      {
        code: 'PRIORITY_RESPECT',
        name: '尊重订单优先级',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 7
      },
      {
        code: 'MIN_MAKESPAN',
        name: '最小总完工时间',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 8
      }
    ],
    version: 1
  },
  {
    id: ID.strategy2,
    name: '瓶颈产能优先',
    direction: 'BOTTLENECK',
    strategyVersion: 2,
    effectiveFrom: '2026-11-01',
    effectiveTo: null,
    initialRules: { sequence: ['PRIORITY', 'DUE_DATE'], defaultSetupMinutes: 45 },
    optimizationRules: { weights: { utilization: 10, tardiness: 5 } },
    applyCondition: { productLines: ['SMT-A'] },
    rules: [
      {
        code: 'MIN_TARDINESS',
        name: '最小超期',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 1
      },
      {
        code: 'EDD',
        name: '交期优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 2
      },
      {
        code: 'SPT',
        name: '短任务优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 3
      },
      {
        code: 'SAME_PRODUCT_CONTINUOUS',
        name: '同品连续生产',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 4
      },
      {
        code: 'MIN_SETUP_TIME',
        name: '最小换型时长',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 5
      },
      {
        code: 'BALANCE_LOAD',
        name: '均衡负载率',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 6
      },
      {
        code: 'PRIORITY_RESPECT',
        name: '尊重订单优先级',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 7
      },
      {
        code: 'MIN_MAKESPAN',
        name: '最小总完工时间',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 8
      }
    ],
    version: 2
  }
]

export const sandboxes: Sandbox[] = [
  {
    id: ID.sandboxBaseline,
    name: '主计划（BASELINE）',
    type: 'BASELINE',
    parentSandboxId: null,
    expiresAt: null,
    snapshot: {
      capturedAt: '2026-11-03T02:00:00Z',
      orderIds: ['SO-20261101-0001', 'SO-20261102-0003'],
      strategyId: ID.strategy1,
      baselineSolveTaskId: 1001,
      note: '默认基线'
    },
    version: 1,
    createdAt: '2026-10-01T00:00:00Z'
  },
  {
    id: ID.sandboxWhatIf,
    name: '加急插单推演',
    type: 'WHAT_IF',
    parentSandboxId: ID.sandboxBaseline,
    expiresAt: '2026-12-31T00:00:00Z',
    snapshot: { capturedAt: NOW, note: 'what-if 推演' },
    version: 1,
    createdAt: NOW
  },
  {
    id: ID.sandboxArchived,
    name: '十月版本归档',
    type: 'ARCHIVED',
    parentSandboxId: ID.sandboxBaseline,
    expiresAt: null,
    snapshot: { capturedAt: '2026-10-25T00:00:00Z', note: '历史版本' },
    version: 1,
    createdAt: '2026-10-25T00:00:00Z'
  }
]

export const solveTaskDetails: SolveTaskDetail[] = [
  {
    id: 1001,
    taskNo: 'SOLVE-20261103-0001',
    sandboxId: ID.sandboxBaseline,
    strategyId: ID.strategy1,
    engine: 'timefold',
    status: 'SOLVED',
    scoreHard: 0,
    scoreSoft: -45,
    startedAt: '2026-11-03T02:00:00Z',
    finishedAt: '2026-11-03T02:00:15Z',
    metrics: { stepCount: 12000, scoreHistory: [0, -120, -80, -45], durationMs: 15000 },
    errorMessage: null,
    createdAt: '2026-11-03T02:00:00Z'
  }
]

export const ganttTasks: GanttTask[] = [
  {
    id: 2101,
    orderNo: 'SO-20261101-0001',
    operationIndex: 1,
    operationName: 'SMT 贴片',
    resourceCode: 'RES-SMT-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T00:00:00Z',
    endTime: '2026-11-05T02:00:00Z',
    setupStartTime: '2026-11-04T23:30:00Z',
    setupEndTime: '2026-11-05T00:00:00Z',
    qty: 120,
    status: 'PLANNED',
    sequenceInResource: 1,
    version: 1
  },
  {
    id: 2102,
    orderNo: 'SO-20261101-0001',
    operationIndex: 2,
    operationName: '电池包总装',
    resourceCode: 'RES-ASSY-01',
    secondaryResourceCodes: ['RES-OP-01'],
    startTime: '2026-11-05T02:30:00Z',
    endTime: '2026-11-05T04:30:00Z',
    setupStartTime: '2026-11-05T02:00:00Z',
    setupEndTime: '2026-11-05T02:30:00Z',
    qty: 120,
    status: 'PLANNED',
    sequenceInResource: 1,
    version: 1
  },
  {
    id: 2103,
    orderNo: 'SO-20261101-0001',
    operationIndex: 3,
    operationName: '老化烘烤',
    resourceCode: 'RES-AGE-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T05:00:00Z',
    endTime: '2026-11-06T05:00:00Z',
    setupStartTime: '2026-11-05T05:00:00Z',
    setupEndTime: '2026-11-05T05:00:00Z',
    qty: 120,
    status: 'FROZEN',
    sequenceInResource: 1,
    version: 2
  },
  {
    id: 2104,
    orderNo: 'SO-20261102-0003',
    operationIndex: 1,
    operationName: 'SMT 贴片',
    resourceCode: 'RES-SMT-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T05:00:00Z',
    endTime: '2026-11-05T06:00:00Z',
    setupStartTime: '2026-11-05T04:30:00Z',
    setupEndTime: '2026-11-05T05:00:00Z',
    qty: 80,
    status: 'IN_PROGRESS',
    sequenceInResource: 2,
    version: 1
  },
  {
    id: 2105,
    orderNo: 'SO-20261102-0003',
    operationIndex: 2,
    operationName: '电池包总装',
    resourceCode: 'RES-ASSY-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T06:30:00Z',
    endTime: '2026-11-05T07:30:00Z',
    setupStartTime: '2026-11-05T06:00:00Z',
    setupEndTime: '2026-11-05T06:30:00Z',
    qty: 80,
    status: 'PLANNED',
    sequenceInResource: 2,
    version: 1
  },
  {
    id: 2106,
    orderNo: 'SO-20261028-0006',
    operationIndex: 1,
    operationName: '模组装配',
    resourceCode: 'RES-ASSY-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-04T08:00:00Z',
    endTime: '2026-11-04T10:00:00Z',
    setupStartTime: '2026-11-04T08:00:00Z',
    setupEndTime: '2026-11-04T08:00:00Z',
    qty: 200,
    status: 'COMPLETED',
    sequenceInResource: 3,
    version: 3
  }
]

export const ganttResourceRows: GanttResourceRow[] = [
  {
    resourceCode: 'RES-SMT-01',
    resourceName: 'SMT 贴片机 1 号',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-SMT-01')
  },
  {
    resourceCode: 'RES-ASSY-01',
    resourceName: '总装线 A',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-ASSY-01')
  },
  {
    resourceCode: 'RES-AGE-01',
    resourceName: '储能老化柜 1 号',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-AGE-01')
  }
]

export const ganttOrderRows: GanttOrderRow[] = [
  {
    orderNo: 'SO-20261101-0001',
    materialCode: 'MAT-FG-001',
    dueDate: '2026-11-12',
    tasks: ganttTasks.filter((task) => task.orderNo === 'SO-20261101-0001')
  },
  {
    orderNo: 'SO-20261102-0003',
    materialCode: 'MAT-FG-001',
    dueDate: '2026-11-08',
    tasks: ganttTasks.filter((task) => task.orderNo === 'SO-20261102-0003')
  }
]

/** MRP 齐套分析 fixture（C25 新契约）。sandboxId/blocking/blocked 由 handler 按请求回填。 */
export const mrpAnalysisFixture: MrpAnalysisResult = {
  sandboxId: '00000000-0000-0000-0000-000000000401',
  analyzedAt: '2026-11-04T02:00:00Z',
  status: 'PARTIAL',
  blocking: false,
  blocked: false,
  inventoryEnabled: true,
  orderCount: 3,
  warnings: ['MAT-RM-101 可用量低于安全库存'],
  orders: [
    {
      orderNo: 'SO-20261102-0010',
      productCode: 'MAT-FG-001',
      qty: 1000,
      dueDate: '2026-11-08',
      kitted: false,
      shortages: [
        {
          materialCode: 'MAT-RM-101',
          materialName: '电芯',
          requiredQty: 1920,
          allocatedQty: 1500,
          shortageQty: 420
        }
      ]
    },
    {
      orderNo: 'SO-20261102-0011',
      productCode: 'MAT-SF-001',
      qty: 800,
      dueDate: '2026-11-10',
      kitted: true,
      shortages: []
    },
    {
      orderNo: 'SO-20261102-0012',
      productCode: 'MAT-FG-002',
      qty: 500,
      dueDate: '2026-11-12',
      kitted: false,
      shortages: [
        {
          materialCode: 'MAT-AX-101',
          materialName: '辅料',
          requiredQty: 60,
          allocatedQty: 40,
          shortageQty: 20
        }
      ]
    }
  ],
  shortages: [
    {
      materialCode: 'MAT-RM-101',
      materialName: '电芯',
      requiredQty: 1920,
      availableQty: 1500,
      shortageQty: 420,
      earliestRequiredAt: '2026-11-06T00:00:00Z',
      affectedOrderNos: ['SO-20261102-0010'],
      suggestedPurchaseDate: '2026-11-05T00:00:00Z',
      suggestedQty: 420,
      supplierCode: 'SUP-CELL-001'
    },
    {
      materialCode: 'MAT-RM-102',
      materialName: '外壳',
      requiredQty: 384,
      availableQty: 1200,
      shortageQty: 0,
      earliestRequiredAt: '2026-11-12T00:00:00Z',
      affectedOrderNos: ['SO-20261102-0011'],
      suggestedPurchaseDate: null,
      suggestedQty: null,
      supplierCode: null
    },
    {
      materialCode: 'MAT-AX-101',
      materialName: '辅料',
      requiredQty: 60,
      availableQty: 40,
      shortageQty: 20,
      earliestRequiredAt: '2026-11-05T00:00:00Z',
      affectedOrderNos: ['SO-20261102-0003'],
      suggestedPurchaseDate: '2026-11-05T00:00:00Z',
      suggestedQty: 20,
      supplierCode: 'SUP-AUX-001'
    }
  ]
}

export const users: User[] = [
  {
    id: ID.user1,
    username: 'admin',
    displayName: '系统管理员',
    email: 'admin@example.com',
    phone: '13800000000',
    status: 'ACTIVE',
    lastLoginAt: NOW,
    version: 1,
    roleCodes: ['ADMIN']
  },
  {
    id: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
    username: 'planner01',
    displayName: '计划员 01',
    email: 'planner01@example.com',
    phone: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-11-03T09:00:00Z',
    version: 1,
    roleCodes: ['PLANNER']
  },
  {
    id: 'ffffffff-ffff-4fff-8fff-fffffffffff2',
    username: 'viewer01',
    displayName: '只读用户',
    email: null,
    phone: null,
    status: 'DISABLED',
    lastLoginAt: null,
    version: 1,
    roleCodes: ['VIEWER']
  }
]

/** C30 权限点：`GET /permissions` 返回的就是这个裸数组。 */
export const permissions: PermissionView[] = [
  { id: 'p01', code: 'MD_READ', name: '主数据查看', module: 'MD' },
  { id: 'p02', code: 'MD_WRITE', name: '主数据维护', module: 'MD' },
  { id: 'p03', code: 'PR_READ', name: '工艺查看', module: 'PR' },
  { id: 'p04', code: 'PR_WRITE', name: '工艺维护', module: 'PR' },
  { id: 'p05', code: 'OM_READ', name: '订单查看', module: 'OM' },
  { id: 'p06', code: 'OM_WRITE', name: '订单维护', module: 'OM' },
  { id: 'p07', code: 'OM_ATP', name: 'ATP 试算', module: 'OM' },
  { id: 'p08', code: 'ORDER_FREEZE', name: '订单冻结', module: 'OM' },
  { id: 'p09', code: 'SS_READ', name: '策略查看', module: 'SS' },
  { id: 'p10', code: 'SS_WRITE', name: '策略维护', module: 'SS' },
  { id: 'p11', code: 'SE_SOLVE', name: '发起求解', module: 'SE' },
  { id: 'p12', code: 'SCHEDULE_ADJUST', name: '排程调整', module: 'SCHEDULE' },
  { id: 'p13', code: 'SCHEDULE_PUBLISH', name: '计划发布', module: 'SCHEDULE' },
  { id: 'p14', code: 'SYS_USER', name: '用户管理', module: 'SYS' },
  { id: 'p15', code: 'SYS_ROLE', name: '角色管理', module: 'SYS' },
  { id: 'p16', code: 'SYS_AUDIT', name: '审计查看', module: 'SYS' },
  { id: 'p17', code: 'AGENT_EVENT', name: 'Agent 事件订阅', module: 'AGENT' },
  { id: 'p18', code: 'AGENT_CONFIRM', name: 'Agent 确认', module: 'AGENT' }
]

/** C30 角色：`GET /roles` 返回 `{items}`，不是裸数组。 */
export const roles: RoleView[] = [
  {
    id: 'r01',
    code: 'ADMIN',
    name: '管理员',
    description: '全部权限',
    permissionCodes: permissions.map((item) => item.code),
    version: 1
  },
  {
    id: 'r02',
    code: 'PLANNER',
    name: '计划员',
    description: '订单、策略、求解与排程',
    permissionCodes: [
      'MD_READ',
      'PR_READ',
      'OM_READ',
      'OM_WRITE',
      'OM_ATP',
      'SS_READ',
      'SS_WRITE',
      'SE_SOLVE',
      'SCHEDULE_ADJUST',
      'SCHEDULE_PUBLISH',
      'AGENT_CONFIRM'
    ],
    version: 1
  },
  {
    id: 'r03',
    code: 'PROCESS_ENGINEER',
    name: '工艺工程师',
    description: '主数据与工艺维护',
    permissionCodes: ['MD_READ', 'MD_WRITE', 'PR_READ', 'PR_WRITE'],
    version: 1
  },
  {
    id: 'r04',
    code: 'SALES',
    name: '销售',
    description: '订单录入与交期试算',
    permissionCodes: ['OM_READ', 'OM_WRITE', 'OM_ATP'],
    version: 1
  },
  {
    id: 'r05',
    code: 'VIEWER',
    name: '只读用户',
    description: '只读',
    permissionCodes: ['MD_READ', 'PR_READ', 'OM_READ', 'SS_READ'],
    version: 1
  }
]

export const auditLogs: AuditLog[] = [
  {
    id: '1024',
    traceId: '56a8a6223cc647898dc11403037d8477',
    userId: ID.user1,
    action: 'AUTH_LOGIN',
    resourceType: 'USER',
    resourceId: ID.user1,
    beforeValue: null,
    afterValue: { username: 'admin', result: 'SUCCESS' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-04T07:55:00Z'
  },
  {
    id: '1025',
    traceId: '8f2c1d4b6a7e4c0d9b3e5f7a1c2d3e4f',
    userId: ID.user1,
    action: 'PLAN_PUBLISHED',
    resourceType: 'PLAN',
    resourceId: ID.sandboxBaseline,
    beforeValue: { versionNo: 1 },
    afterValue: { versionNo: 2, planNo: 'PLAN-20261104-0002' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-04T06:30:00Z'
  },
  {
    id: '1026',
    traceId: '1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d',
    userId: ID.user1,
    action: 'ORDER_FROZEN',
    resourceType: 'ORDER',
    resourceId: 'SO-20261104-0008',
    beforeValue: { frozen: false },
    afterValue: { frozen: true, reason: '客户确认插单' },
    clientIp: '10.0.0.20',
    createdAt: '2026-11-04T05:10:00Z'
  },
  {
    id: '1027',
    traceId: '2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e',
    userId: ID.user1,
    action: 'ORDER_RELEASED',
    resourceType: 'ORDER',
    resourceId: 'SO-20261103-0004',
    beforeValue: { status: 'HOLD' },
    afterValue: { status: 'RELEASED' },
    clientIp: '10.0.0.20',
    createdAt: '2026-11-03T09:40:00Z'
  },
  {
    id: '1028',
    traceId: '3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f',
    userId: ID.user1,
    action: 'SANDBOX_APPLIED',
    resourceType: 'SANDBOX',
    resourceId: ID.sandboxWhatIf,
    beforeValue: { type: 'WHAT_IF' },
    afterValue: { type: 'BASELINE' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-03T03:15:00Z'
  },
  {
    id: '1029',
    traceId: '4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a',
    userId: ID.user1,
    action: 'USER_UPDATED',
    resourceType: 'USER',
    resourceId: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
    beforeValue: { roleCodes: ['VIEWER'] },
    afterValue: { roleCodes: ['PLANNER'] },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-02T08:20:00Z'
  },
  {
    id: '1030',
    traceId: '5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b',
    userId: null,
    action: 'AUTH_LOGIN_FAILED',
    resourceType: 'USER',
    resourceId: 'unknown',
    beforeValue: null,
    afterValue: { username: 'admin', reason: 'BAD_CREDENTIALS' },
    clientIp: '10.0.0.34',
    createdAt: '2026-11-01T23:59:00Z'
  },
  {
    id: '1031',
    traceId: '6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c',
    userId: ID.user1,
    action: 'TASK_ADJUSTED',
    resourceType: 'TASK',
    resourceId: '2101',
    beforeValue: { startTime: '2026-11-05T00:00:00Z' },
    afterValue: { startTime: '2026-11-05T01:00:00Z' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-01T10:05:00Z'
  }
]

export const configs: SystemConfig[] = [
  {
    key: 'solver.defaultEngine',
    value: 'timefold',
    description: '默认求解引擎',
    updatedAt: NOW
  },
  {
    key: 'solver.timeoutSeconds',
    value: '60',
    description: '求解超时（秒）',
    updatedAt: NOW
  },
  {
    key: 'planning.horizonDays',
    value: '30',
    description: '排产滚动窗口（天）',
    updatedAt: NOW
  },
  {
    key: 'features.agent',
    value: 'true',
    description: 'Agent 功能开关',
    updatedAt: NOW
  }
]

export const planId = ID.plan
export const currentTimestamp = NOW
