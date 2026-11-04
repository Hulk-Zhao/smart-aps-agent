import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  AuditLog,
  ConfigUpdateRequest,
  Health,
  LoginRequest,
  LoginResult,
  SystemConfig,
  User,
  UserCreateRequest
} from '@/types/system'

export async function fetchHealth(): Promise<Health> {
  const response = await http.get<Health>('/health')
  return response.data
}

export async function login(payload: LoginRequest): Promise<LoginResult> {
  const response = await http.post<LoginResult>('/auth/login', payload)
  return response.data
}

export async function listUsers(query: PageQuery = {}): Promise<PageResult<User>> {
  const response = await http.get<PageResult<User>>('/users', { params: withPageQuery(query) })
  return response.data
}

export async function createUser(
  payload: UserCreateRequest,
  idempotencyKey?: string
): Promise<User> {
  const response = await http.post<User>('/users', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function listAuditLogs(query: PageQuery = {}): Promise<PageResult<AuditLog>> {
  const response = await http.get<PageResult<AuditLog>>('/audit-logs', {
    params: withPageQuery(query)
  })
  return response.data
}

export async function getConfigs(): Promise<SystemConfig[]> {
  const response = await http.get<SystemConfig[]>('/configs')
  return response.data
}

export async function updateConfigs(
  payload: ConfigUpdateRequest,
  idempotencyKey?: string
): Promise<SystemConfig[]> {
  const response = await http.put<SystemConfig[]>('/configs', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}
