#!/usr/bin/env bash
# 一键启动 APS 整栈（PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend）
# 等价 PowerShell 脚本：scripts/stack-up.ps1
#
# 用法：
#   ./scripts/stack-up.sh                 # 构建镜像 + 启动 + 等待健康检查 + 建 MinIO 桶
#   ./scripts/stack-up.sh --skip-build    # 使用已有镜像，不强制重建
#   ./scripts/stack-up.sh --skip-pull     # 跳过中间件镜像拉取（离线/已缓存）
#   ./scripts/stack-up.sh --no-wait       # 只 up -d，不等待健康检查，也不建桶
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SKIP_BUILD=0
SKIP_PULL=0
NO_WAIT=0

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --skip-pull) SKIP_PULL=1 ;;
        --no-wait) NO_WAIT=1 ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        *)
            echo "[ERROR] 未知参数: $arg（支持 --skip-build / --skip-pull / --no-wait）" >&2
            exit 2
            ;;
    esac
done

step() { printf '\033[36m[..] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m[WARN] %s\033[0m\n' "$*"; }
fail() {
    printf '\033[31m[ERROR] %s\033[0m\n' "$*" >&2
    shift || true
    for hint in "$@"; do printf '  - %s\n' "$hint" >&2; done
    exit 1
}

env_value() {
    local key="$1" default="$2" value=""
    if [[ -f .env ]]; then
        value="$(grep -E "^[[:space:]]*${key}=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//' || true)"
    fi
    printf '%s' "${value:-$default}"
}

port_conflict() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$"; then
            echo "端口已被占用"
            return 0
        fi
    elif command -v lsof >/dev/null 2>&1; then
        if lsof -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
            echo "端口已被占用"
            return 0
        fi
    else
        if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
            exec 3>&-
            echo "端口已被占用"
            return 0
        fi
    fi
    return 1
}

step '检查 Docker CLI'
command -v docker >/dev/null 2>&1 || fail '未找到 docker 命令' \
    '安装 Docker Desktop（含 Compose v2）：https://docs.docker.com/desktop/install/' \
    '安装完成后重新打开终端再试'

step '检查 Docker 守护进程'
docker info >/dev/null 2>&1 || fail 'Docker 守护进程未运行（docker info 失败）' \
    '启动 Docker Desktop / systemctl start docker，等待就绪后重试' \
    '确认当前用户有访问 docker socket 的权限（Linux: 加入 docker 组）'

step '检查 Docker Compose v2'
docker compose version >/dev/null 2>&1 || fail '未检测到 docker compose v2 插件' \
    'Docker Desktop 自带 compose v2；Linux 安装 docker-compose-plugin' \
    '不要使用旧版 docker-compose（v1）执行本脚本'

step '准备 .env'
if [[ ! -f .env ]]; then
    [[ -f .env.example ]] || fail '缺少 .env 与 .env.example，无法启动' '请从版本库恢复 .env.example 后重试'
    cp .env.example .env
    warn '未找到 .env，已从 .env.example 复制；上线前务必替换所有默认密码与 JWT secret'
fi

# 显式 --project-name：根 .env 的 COMPOSE_PROJECT_NAME=aps-dev 会覆盖 compose 顶层 name，
# 不指定会与 dev 栈共享 project label（down --remove-orphans 有误删风险）。
COMPOSE=(compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml)

frontend_port="$(env_value APS_FRONTEND_PORT 80)"
backend_port="$(env_value APS_BACKEND_PORT 8080)"

step '检查入口端口占用'
conflicts=()
if owner="$(port_conflict "$frontend_port")"; then
    conflicts+=("前端入口 ${frontend_port} -> ${owner}")
else
    printf '    前端入口 %s 可用\n' "$frontend_port"
fi
if owner="$(port_conflict "$backend_port")"; then
    conflicts+=("后端 API ${backend_port} -> ${owner}")
else
    printf '    后端 API %s 可用\n' "$backend_port"
fi
if [[ "${#conflicts[@]}" -gt 0 ]]; then
    fail '入口端口冲突，已中止启动（未改动任何运行中的容器）' \
        "${conflicts[@]}" \
        '在 .env 中设置 APS_FRONTEND_PORT / APS_BACKEND_PORT 换用其他端口后重试' \
        '或停止占用该端口的程序（Linux 上 80 端口常被已有 Nginx/Apache 占用）'
fi

if [[ "$SKIP_PULL" -eq 0 ]]; then
    step '拉取中间件镜像（首次执行耗时较长）'
    if ! docker "${COMPOSE[@]}" pull postgres redis minio; then
        fail '中间件镜像拉取失败' \
            '国内网络为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890' \
            'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE' \
            '镜像已缓存时可加 --skip-pull 跳过拉取'
    fi
fi

# 只对长期运行的服务做 --wait；minio-init 是 profiles: init 的一次性任务，启动后单独执行
UP=(up -d)
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    UP+=(--build)
fi
if [[ "$NO_WAIT" -eq 0 ]]; then
    UP+=(--wait --wait-timeout 300)
fi
UP+=(postgres redis minio aps-backend aps-frontend)

step '构建镜像、启动容器并等待健康检查'
if ! docker "${COMPOSE[@]}" "${UP[@]}"; then
    fail '整栈启动或健康检查未通过' \
        '查看状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps' \
        '查看日志：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs postgres redis minio aps-backend aps-frontend' \
        '后端启动失败优先看 Flyway 迁移日志，以及 aps-admin/Dockerfile 是否把 db/migration 打进镜像' \
        '镜像拉取失败请检查 Docker Desktop 代理（http://127.0.0.1:7890）或 MINIO_IMAGE'
fi

if [[ "$NO_WAIT" -eq 0 ]]; then
    step '初始化 MinIO 附件桶'
    if ! docker "${COMPOSE[@]}" --profile init run --rm --no-deps -T minio-init; then
        warn 'MinIO 附件桶初始化失败（不影响其他服务运行）'
        warn '可手动重试：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml --profile init run --rm minio-init'
    fi
fi

echo
printf '\033[32mAPS 整栈已启动\033[0m\n'
echo "  前端入口   http://localhost:${frontend_port}"
echo "  后端健康   http://localhost:${backend_port}/api/v1/health"
echo '  中间件     postgres:5432 / redis:6379 / minio:9000（仅容器内网）'
if [[ "$NO_WAIT" -eq 1 ]]; then
    printf '\033[33m  当前未等待健康检查，也未初始化 MinIO 桶；请查看 ps 状态后再做验收。\033[0m\n'
fi
echo
echo '状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps'
echo '停止：./scripts/stack-down.sh            （保留数据卷）'
echo '清数据：./scripts/stack-down.sh --remove-volumes'
