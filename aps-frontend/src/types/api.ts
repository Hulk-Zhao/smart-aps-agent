export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface HealthStatus {
  status: string
  service: string
}

export interface ProductionOrder {
  orderNo: string
  materialCode: string
  qty: number
  status: string
  freezeStatus: string
  dueDate: string
}

export interface SolveRequest {
  taskNo: string
  engine: string
  orderNos: string[]
  parameters: Record<string, unknown>
}

export interface ScheduledTask {
  orderNo: string
  resourceCode: string
  startTime: string
  endTime: string
}

export interface SolveResult {
  taskNo: string
  status: string
  tasks: ScheduledTask[]
  message: string
}
