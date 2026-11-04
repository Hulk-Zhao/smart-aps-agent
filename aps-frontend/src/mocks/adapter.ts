import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse } from 'axios'

import { handleMockRequest } from './handlers'

const configuredDelay = Number(import.meta.env.VITE_MOCK_DELAY)
const delayMs = Number.isFinite(configuredDelay) ? Math.max(configuredDelay, 0) : 300

function statusText(status: number): string {
  if (status < 400) {
    return 'OK'
  }
  return status === 501 ? 'Not Implemented' : 'Mock Error'
}

/** Axios 自定义适配器：命中契约 Mock 时返回 Result 信封，未命中返回 404。 */
export const mockAdapter: AxiosAdapter = async (config) => {
  if (delayMs > 0) {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
  }
  const reply = handleMockRequest(config)
  const response: AxiosResponse = {
    data: reply.body,
    status: reply.status,
    statusText: statusText(reply.status),
    headers: new AxiosHeaders({ 'x-trace-id': reply.body.traceId }),
    config,
    request: {}
  }
  if (reply.status >= 400) {
    throw new AxiosError(reply.body.message, String(reply.status), config, {}, response)
  }
  return response
}
