/**
 * system 域类型，字段对齐 docs/api/openapi/system.yaml；
 * users.status 取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

export interface Health {
  status: string
  service: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  userId: string
}

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED'

export interface User {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  phone?: string | null
  status: UserStatus
  lastLoginAt?: string | null
  version: number
}

export interface UserCreateRequest {
  username: string
  displayName?: string
  email?: string
  phone?: string
  password?: string
  roleCodes?: string[]
}

export interface AuditLog {
  id: string
  traceId: string
  userId?: string | null
  action: string
  resourceType: string
  resourceId: string
  beforeValue?: Record<string, unknown> | null
  afterValue?: Record<string, unknown> | null
  clientIp?: string | null
  createdAt: string
}

export interface SystemConfig {
  key: string
  value: string
  description?: string | null
  updatedAt: string
}

export interface ConfigUpdateRequest {
  items: Array<{ key: string; value: string }>
}
