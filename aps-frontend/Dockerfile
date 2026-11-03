# =============================================================================
# 衡策 APS 前端镜像（Vue3 + Vite 构建，Nginx 托管）
#   构建上下文为 aps-frontend 目录：
#     docker build -f aps-frontend/Dockerfile -t aps-frontend:local aps-frontend
# =============================================================================
# syntax=docker/dockerfile:1.7

# --------------------------- 构建阶段 ---------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

ENV NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false

# 依赖层独立缓存
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# --------------------------- 运行阶段 ---------------------------------------
FROM nginx:alpine AS runtime

# 后端地址由 compose/运行时注入；默认指向 compose 内服务名 aps-admin
ENV APS_BACKEND_URL=http://aps-admin:8080

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1
