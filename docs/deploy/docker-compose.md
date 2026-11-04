# 一键整栈（Docker Compose）

> 任务：M1-QA-02「Docker Compose 编排」
> 适用范围：单机一条命令拉起 PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend，用于联调与冒烟验收。
> 架构依据：[aps-arch-page](../../aps-arch-page/public/index.html) 5.1「Docker Compose 服务清单与端口规划」（只读，不改动）。
> 相关文档：[local-dev.md](local-dev.md)（只含中间件的本地开发栈，本任务未改动该文件）。

## 1. 与 dev 基础设施栈的区别

| | `deploy/docker-compose.dev.yml` | `deploy/docker-compose.yml`（本任务） |
| --- | --- | --- |
| 编排范围 | 仅 PostgreSQL / Redis / MinIO | 中间件 + aps-backend + aps-frontend（+ 可选 minio-init） |
| 使用者 | 宿主机 IDE 中运行前后端的开发者 | 需要「一条命令得到可访问环境」的联调 / 验收 |
| 工程名 | `aps-dev`（`COMPOSE_PROJECT_NAME`） | 固定 `aps-stack` |
| 宿主端口 | 默认不发布，调试时叠加 expose 文件并只绑 `127.0.0.1` | 前端 80、后端 8080；中间件仅内网 |
| 启动脚本 | `scripts/dev-up.*` / `dev-down.*` | `scripts/stack-up.*` / `stack-down.*` |

**为什么新增 `stack-*` 而不是复用 `dev-*`**：两者编排的服务集合、端口策略、工程名和是否构建镜像都不同；复用会让 dev 脚本出现「是否要构建前后端」的分支，容易误启动/误停栈。新脚本沿用 dev 脚本的检查项、中文错误提示与参数命名，学习成本不变。

## 2. 交付文件

| 文件 | 作用 |
| --- | --- |
| `deploy/docker-compose.yml` | 一键整栈编排：服务、网络、命名卷、健康检查、依赖条件、日志轮转 |
| `scripts/stack-up.ps1` / `stack-up.sh` | 一键启动：环境/端口检查 → 拉中间件镜像 → 构建前后端 → `up --wait` → 建 MinIO 桶 |
| `scripts/stack-down.ps1` / `stack-down.sh` | 一键停止（默认保留数据卷，可选删除） |
| `docs/deploy/docker-compose.md` | 本文档：拓扑、端口、卷、环境变量对应关系、验证记录 |
| `docs/deploy/smoke-checklist.md` | 起栈后的验收清单与可复现命令 |

未改动的既有文件：`aps-admin/Dockerfile`、`aps-frontend/Dockerfile`、`aps-frontend/nginx/default.conf.template`、`.env.example`、`application*.yml`、`db/migration`、`deploy/docker-compose.dev*.yml`、`docs/deploy/local-dev.md`。

## 3. 服务、端口与卷拓扑

```text
                宿主机
  :80  ┌─────────────────────────┐
       │ aps-frontend (nginx)    │  /api/* 反代到 aps-backend:8080
       └───────────┬─────────────┘
                   │ aps-stack-aps-net（仅此 bridge 网络）
  :8080 ┌──────────┴─────────────┐
        │ aps-backend (Spring)   │  Flyway 启动迁移
        └───┬────────┬────────┬──┘
            │        │        │
      postgres   redis    minio        （均不发布宿主端口）
       5432      6379    9000/9001
```

| 服务 | 构建 / 镜像 | 容器端口 | 宿主映射 | 命名卷 | 健康检查 | depends_on |
| --- | --- | --- | --- | --- | --- | --- |
| `aps-frontend` | `aps-frontend/Dockerfile`，上下文 `aps-frontend/` | 8080 | `${APS_FRONTEND_PORT:-80}` → 容器 8080 | — | `wget http://127.0.0.1:8080/healthz` | `aps-backend: service_healthy` |
| `aps-backend` | `aps-admin/Dockerfile`，上下文仓库根目录 | 8080 | `${APS_BACKEND_PORT:-8080}` | — | `curl /api/v1/health` | postgres/redis/minio `service_healthy` |
| `postgres` | `postgres:15` | 5432 | 无（内网） | `aps-stack-pgdata` | `pg_isready` | — |
| `redis` | `redis:7` | 6379 | 无（内网） | `aps-stack-redisdata` | `redis-cli ping` | — |
| `minio` | `${MINIO_IMAGE:-pgsty/minio:RELEASE.2026-08-04T00-00-00Z}` | 9000 / 9001 | 无（内网） | `aps-stack-miniodata` | `curl /minio/health/live` | — |
| `minio-init` | 同 MinIO 镜像（内置 `mc`） | — | — | — | —（一次性任务） | `minio: service_healthy` |

说明：

- 网络固定为 `aps-stack-aps-net`，容器名固定为 `aps-stack-*`，卷固定为 `aps-stack-*`，与 dev 栈的 `aps-dev-*` 完全隔离。
- `minio-init` 放在 `profiles: ["init"]`，避免普通 `up --wait` 把「已正常退出的一次性容器」判为未就绪；由 `stack-up` 显式执行 `--profile init run --rm --no-deps -T minio-init`。
- 后端日志按 `logback-spring.xml` 统一输出到 stdout，因此不创建架构页中的 `logs` 卷；容器日志由 `json-file` 驱动轮转（`max-size=10m`、`max-file=3`）。
- 中间件全部只 `expose`、不 `ports`，符合架构页「仅前端 80 对外，backend/db/redis/minio 内网」的要求；后端 8080 按任务要求发布，架构页允许「8080 或仅内网」。
- 前端运行镜像为 `nginxinc/nginx-unprivileged:1.31-alpine`（uid 101），只能监听非特权端口，因此**容器内监听 8080**，宿主 `${APS_FRONTEND_PORT:-80}` 映射到容器 8080；容器健康检查也打 `127.0.0.1:8080/healthz`。
- `aps-backend` 的 `start_period` 为 90s，给首次 Flyway 迁移（28 张表）留足冷启动时间。

### 3.1 工程名必须显式指定（重要）

根目录 `.env` 中的 `COMPOSE_PROJECT_NAME=aps-dev` **优先级高于 compose 文件里的顶层 `name`**。若直接用 `docker compose -f deploy/docker-compose.yml up`，该栈会被归入 `aps-dev` 工程，`down --remove-orphans` 可能把 dev 栈的容器当作孤儿删除。

因此 `stack-up.*` / `stack-down.*` 与本文所有手动命令都显式带 `--project-name aps-stack`。手动执行时不要省略。

## 4. 快速开始

```powershell
# 仓库根目录执行
.\scripts\stack-up.ps1            # 构建 + 启动 + 等待健康检查 + 建 MinIO 桶
.\scripts\stack-up.ps1 -SkipPull  # 镜像已缓存/离线
.\scripts\stack-up.ps1 -NoWait    # 只 up -d，不等健康检查、不建桶
.\scripts\stack-down.ps1          # 停止，保留数据卷
.\scripts\stack-down.ps1 -RemoveVolumes
```

```bash
./scripts/stack-up.sh
./scripts/stack-up.sh --skip-pull
./scripts/stack-down.sh
./scripts/stack-down.sh --remove-volumes
```

脚本会：检查 Docker CLI / 守护进程 / Compose v2 → 缺失 `.env` 时从 `.env.example` 复制 → 检查 80/8080 占用 → 拉取中间件镜像 → `up -d --build --wait` → 建桶。守护进程未运行时会在启动前给出中文排查提示并退出（exit 1），不会留下半启动状态。

手动执行等价命令：

```powershell
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml `
  up -d --build --wait --wait-timeout 300 postgres redis minio aps-backend aps-frontend
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml `
  --profile init run --rm --no-deps -T minio-init
```

## 5. 环境变量与 `.env.example` 的对应关系

本栈不修改 `.env.example`（属 P0-06 交付物），直接复用其中的变量名；Compose 在启动时把「宿主机视角」的地址改写成容器内服务名。

| `.env.example` 变量 | 本栈消费方 | 本栈中的实际取值 / 说明 |
| --- | --- | --- |
| `COMPOSE_PROJECT_NAME` | Compose | **不用于本栈**；本栈固定 `--project-name aps-stack`，避免与 dev 栈混用 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres、aps-backend | 初始化库/账号；后端同时注入 `DB_*` 与 `SPRING_DATASOURCE_*` |
| `REDIS_PASSWORD` | redis、aps-backend | `--requirepass` 与 `SPRING_DATA_REDIS_PASSWORD` 共用 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | minio、minio-init、aps-backend | 根账号；后端的 `APS_MINIO_ACCESS_KEY/SECRET_KEY` 由这两个值派生 |
| `MINIO_BUCKET` | minio-init、aps-backend | 默认 `aps-attachments` |
| `MINIO_IMAGE` | Compose | 覆盖 MinIO 镜像（内网 Harbor / 自建）；需同时提供 `mc` 供 minio-init 使用 |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | aps-backend | `.env` 中的 `localhost` 版本**不直接使用**；Compose 改写为 `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`，用户名/密码取 `POSTGRES_*` |
| `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` | aps-backend | host 改写为 `redis`，端口 `6379`，密码取 `REDIS_PASSWORD` |
| `APS_MINIO_ENDPOINT` | aps-backend | 改写为 `http://minio:9000` |
| `APS_MINIO_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | aps-backend | 由 `MINIO_ROOT_*` / `MINIO_BUCKET` 派生（请保持两者一致） |
| `APS_JWT_SECRET` / `APS_JWT_EXPIRE_MINUTES` | aps-backend | 原样注入，secret 为必填 |
| `POSTGRES_DEBUG_PORT` 等 4 个 | — | 本栈不发布中间件端口，不使用 |

本栈新增的可选变量（不写入 `.env.example`，需要时手工追加到 `.env`；未设置则用默认值）：

```dotenv
# 前端/后端宿主端口（默认 80 / 8080；前端容器内固定监听 8080）
APS_FRONTEND_PORT=80
APS_BACKEND_PORT=8080
# Flyway 自动迁移开关（默认 true；排障时可临时关掉）
APS_FLYWAY_ENABLED=true
# Spring profile（默认 dev，用于加载 application-dev.yml 的 Flyway 配置）
APS_SPRING_PROFILES=dev
# 自定义镜像名（便于内网仓库/离线包）
APS_BACKEND_IMAGE=aps-stack/aps-backend:local
APS_FRONTEND_IMAGE=aps-stack/aps-frontend:local
```

必填变量（缺失时 Compose 会直接报错并提示复制 `.env.example`）：`POSTGRES_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ROOT_PASSWORD`、`APS_JWT_SECRET`。

**当前后端配置消费情况**：`application.yml` / `application-dev.yml` 实际读取的是 `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` 与 `SPRING_*` 标准变量；Redis、MinIO、JWT 相关变量目前只是按 `.env.example` 契约注入，后端尚未引入对应客户端/配置项。变量名改名需前后端同步。

## 6. Flyway 自动迁移

- `SPRING_PROFILES_ACTIVE=dev` 加载 `application-dev.yml`（`spring.flyway.enabled=true`、location `classpath:db/migration`）。
- `SPRING_FLYWAY_ENABLED` 提供独立的开关（默认 `true`）。
- `aps-admin/Dockerfile` 已新增 `COPY db/migration db/migration`，V1 迁移随镜像进入 classpath；compose 使用 Spring 默认的 `classpath:db/migration`，**不再挂载宿主机目录，也不再设置 `SPRING_FLYWAY_LOCATIONS`**。
- 修改 `db/migration` 后必须重新构建后端镜像（`stack-up` 默认带 `--build`），旧镜像仍使用构建时的迁移脚本。
- 空库首次启动预期：`flyway_schema_history` 有 1 条 `V1` 成功记录，`public` 下业务表 28 张（不含 `flyway_schema_history`）。验收见 [smoke-checklist.md](smoke-checklist.md)。

## 7. 日志与运维

- 所有服务使用 `json-file` + `max-size=10m` / `max-file=3`，单机长期运行不会写满磁盘。
- 后端 `logback-spring.xml`：非 prod 输出单行控制台日志，prod 输出 JSON，均由 `docker logs` 采集。
- 常用命令（均需带 `--project-name aps-stack`）：

```powershell
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs -f aps-backend aps-frontend
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml restart aps-backend
```

## 8. Dockerfile 对接问题清单（1~3 已修复，其余为观察项）

| # | 状态 | 文件 | 原问题 → 修复结果 / 现状 |
| --- | --- | --- | --- |
| 1 | 已修复 | `aps-admin/Dockerfile` | 原未复制 `db/migration`，镜像内没有迁移脚本 → 已新增 `COPY db/migration db/migration`，V1 随镜像进入 classpath；compose 已删除只读挂载与 `SPRING_FLYWAY_LOCATIONS` |
| 2 | 已修复 | `aps-frontend/Dockerfile` | 原默认 `APS_BACKEND_URL=http://aps-admin:8080` → 已改为 `http://aps-backend:8080`，与 compose 服务名一致 |
| 3 | 已修复 | `aps-frontend/Dockerfile` | 原 `nginx:alpine` 以 root 运行、容器端口 80 → 已改为 `nginxinc/nginx-unprivileged:1.31-alpine`，容器监听 8080；compose 改为宿主 80 → 容器 8080，健康检查同步 8080 |
| 4 | 保持 | `aps-admin/Dockerfile` | 非 root（uid 1001）、jar 名 `aps-admin-*.jar`、`curl` HEALTHCHECK 均正常，与 compose 健康检查一致 |
| 5 | 观察 | `application.yml` | `/api/v1/health` 只返回进程存活，不检查数据库/迁移状态；验收必须单独查表（见 smoke checklist） |
| 6 | 保持 | `aps-frontend/.dockerignore` | 已排除 `node_modules` / `dist`，构建上下文干净 |
| 7 | 观察 | `MINIO_IMAGE` 覆盖 | 若换成不含 `mc` 的镜像，`minio-init` 需改用独立的 `minio/mc` 镜像执行建桶 |

> **Dockerfile 已修复，原先的 Flyway 挂载兜底已移除**：`aps-backend` 不再声明 `../db/migration:/app/db/migration:ro` 卷，也不再设置 `SPRING_FLYWAY_LOCATIONS=filesystem:/app/db/migration`，迁移脚本由镜像内的 `classpath:db/migration` 提供。

## 9. 本次静态验证记录（2026-11-04）

本机 Docker CLI 与 Compose v2（`Docker Compose version v5.5.1`）可用，但 **Docker 守护进程未运行**：`docker info` 报 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`，`com.docker.service` 未启动且当前无提权。因此 **未实机构建镜像、未起栈、未执行健康检查**。

已执行：

| 命令 | 结果 |
| --- | --- |
| `docker compose --env-file .env.example --project-name aps-stack -f deploy/docker-compose.yml config --quiet` | exit 0 |
| 上述命令追加 `--profile init` | exit 0（`minio-init` 可解析） |
| `docker compose ... config` 解析检查 | 工程名 `aps-stack`；容器 `aps-stack-{backend,frontend,postgres,redis,minio}`；网络 `aps-stack-aps-net`；卷 `aps-stack-{pgdata,redisdata,miniodata}`；端口 `80:8080`（前端）、`8080:8080`（后端）；依赖条件均为 `service_healthy` |
| `python -c "yaml.safe_load(...)"` | YAML 解析通过，6 个服务 |
| `"C:\Program Files\Git\bin\bash.exe" -n scripts/stack-up.sh` / `stack-down.sh` | exit 0 / exit 0 |
| PowerShell AST `Parser::ParseFile` 两个脚本 | 无语法错误 |
| `powershell -File scripts/stack-up.ps1 -SkipPull -NoWait` | 按预期在守护进程检查处退出（exit 1），提示可读 |
| `powershell -File scripts/stack-down.ps1` | 同上（exit 1） |

**Dockerfile 修复后复核**：重新执行上述两条 `config --quiet`（含 `--profile init`）与 PyYAML 解析，均 exit 0；解析结果确认前端 `80:8080`、healthcheck `http://127.0.0.1:8080/healthz`，`aps-backend` 无 `volumes`、无 `SPRING_FLYWAY_LOCATIONS`。Docker 守护进程仍未运行，因此仍未实机构建/起栈。

有 Docker 环境的机器上可复现的完整验收：

```powershell
Copy-Item .env.example .env
.\scripts\stack-up.ps1
.\scripts\stack-down.ps1 -RemoveVolumes
```

## 10. 常见问题

1. **端口 80 被占用**：Windows 常见于 IIS / `http.sys`；改 `.env` 的 `APS_FRONTEND_PORT` 后重试，脚本会在启动前检查并中止。
2. **`down` 担心误删 dev 栈**：必须带 `--project-name aps-stack`（脚本已内置）。不要省略工程名。
3. **后端反复重启**：`logs aps-backend` 看 Flyway。迁移脚本已由 Dockerfile 打进镜像；若提示找不到 `db/migration`，确认重新构建了后端镜像（`stack-up` 默认带 `--build`），而不是复用旧镜像。
4. **前端 502**：`docker compose ... exec aps-frontend wget -qO- http://aps-backend:8080/api/v1/health`；再确认 `APS_BACKEND_URL` 未被改成 `aps-admin`。
5. **镜像拉取慢/失败**：Docker Desktop → Settings → Resources → Proxies 配 `http://127.0.0.1:7890`；或用 `-SkipPull` / 内网 Harbor 的 `MINIO_IMAGE`。
6. **改密码后旧库不生效**：PostgreSQL 只在空卷初始化；改 `.env` 密码需 `stack-down.ps1 -RemoveVolumes` 后重建，或手工改库。

## 11. 边界与后续

- 本任务未修改任何 Dockerfile、pom、`application*.yml`、`db/migration`、CI 配置、前端源码、架构页、PRD/调研报告；也未执行 git commit/push/init。
- 首轮记录的 Dockerfile 问题 1~3 已由负责方修复；本任务仅同步 compose（移除 Flyway 挂载兜底、前端端口改为 80→8080）与文档，未改动 Dockerfile。
- `minio-init` 依赖 MinIO 镜像内置的 `mc`；若通过 `MINIO_IMAGE` 换成不含 `mc` 的官方镜像，需要改用独立的 `minio/mc` 镜像执行建桶。
- M2-QA-03 的离线部署包可直接复用本编排与 `stack-*` 脚本，并将 `APS_BACKEND_IMAGE` / `APS_FRONTEND_IMAGE` 指向离线导入的镜像。
