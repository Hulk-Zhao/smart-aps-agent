import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  // 仅开发态：C27 后端在 security.mode=dev 时要求写请求带 X-API-Key。
  // 密钥来源：进程环境变量 APS_DEV_API_KEY 或 .env*.local（*.local 已被 .gitignore 覆盖）。
  // 未配置时不注入任何头；生产走网关/JWT，不经过该代理，也不会带此头。
  const env = loadEnv(mode, process.cwd(), 'APS_')
  const devApiKey = env.APS_DEV_API_KEY?.trim()
  const devApiHeaders = devApiKey ? { 'X-API-Key': devApiKey } : undefined

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          // 本机联调使用 dev profile 真库后端（18085），避免与其他会话的 8080/1808x 冲突。
          target: 'http://127.0.0.1:18085',
          changeOrigin: true,
          ...(devApiHeaders ? { headers: devApiHeaders } : {})
        },
        '/ws': {
          // C20 求解进度 STOMP：与 /api 同源后端，必须开启 ws 升级。
          target: 'http://127.0.0.1:18085',
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
