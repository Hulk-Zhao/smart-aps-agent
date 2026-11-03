import http from './http'
import type {
  ApiResponse,
  HealthStatus,
  ProductionOrder,
  SolveRequest,
  SolveResult
} from '@/types/api'

export async function fetchHealth(): Promise<ApiResponse<HealthStatus>> {
  const response = await http.get<ApiResponse<HealthStatus>>('/health')
  return response.data
}

export async function fetchOrders(): Promise<ApiResponse<ProductionOrder[]>> {
  const response = await http.get<ApiResponse<ProductionOrder[]>>('/orders')
  return response.data
}

export async function submitSolveTask(payload: SolveRequest): Promise<ApiResponse<SolveResult>> {
  const response = await http.post<ApiResponse<SolveResult>>('/solve-tasks', payload)
  return response.data
}
