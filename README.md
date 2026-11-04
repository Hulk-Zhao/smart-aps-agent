# 衡策 APS 开源版（mfg-aps-platform）

面向离散制造（SMT 电子组装、储能 PACK）的开源智能排产系统，定位为 ERP 与 MES 之间的「计划大脑」。
本仓库当前处于 v0.1 MVP 工程基座阶段，包含后端分层骨架与前端 Vue3 骨架。

## 模块结构

| 模块 | 职责 | 关键依赖方向 |
|---|---|---|
| `aps-common` | 公共响应、异常、常量 | 无内部依赖 |
| `aps-domain` | 领域模型与仓储抽象 | → aps-common |
| `aps-infrastructure` | 持久化、缓存、集成实现 | → aps-domain / aps-common |
| `aps-solver/solver-api` | `SolverAdapter` 统一抽象 | → aps-common |
| `aps-solver/solver-dsl` | 约束 DSL 模型与加载入口 | → solver-api |
| `aps-solver/solver-timefold` | Timefold 引擎实现 | → solver-api |
| `aps-solver/solver-ortools` | OR-Tools CP-SAT 实现（预留） | → solver-api |
| `aps-service` | 业务服务编排 | → domain / solver-api / infrastructure |
| `aps-web` | REST Controller 与统一响应 | → aps-service |
| `aps-admin` | 启动入口与求解器装配（组合根） | → aps-web / 引擎实现 |
| `aps-frontend` | Vue 3 + TypeScript + Vite 前端 | 独立构建 |

约束：Timefold 类型只允许出现在 `solver-timefold`，不得泄漏到 `aps-service` / `aps-web`。

技术栈：Spring Boot 3.3.13、Java 21、Maven 3.9+、Timefold Solver 2.8.0、MyBatis-Plus 3.5.9、Testcontainers 1.21.4、Vue 3.5、Vite 8、Element Plus、Pinia、Vue Router、Axios。

## M1 契约冻结

- 契约基线：[docs/api/contract-freeze.md](docs/api/contract-freeze.md)；枚举字典：[docs/api/enums.md](docs/api/enums.md)。
- **业务流不得编辑** `aps-web/src/main/java/com/hengce/aps/web/controller/contract/**` 与 `docs/api/openapi/*.yaml`；字段变更走契约变更单。
- 数据库迁移号段见 [docs/db/README.md](docs/db/README.md)：M1 默认零迁移，只有 S0 发号，禁止修改 `V1__init.sql`。

## 本地启动

### 后端

```bash
mvn -DskipTests package
mvn -pl aps-admin -am spring-boot:run
```

健康检查：`GET http://localhost:8080/api/v1/health`

### 前端

```bash
cd aps-frontend
npm install
npm run dev
```

默认后端地址通过 `VITE_API_BASE_URL` 配置（见 `aps-frontend/.env.development`）。

## 代码规范与协作

- 编码规范：[docs/coding-standard.md](docs/coding-standard.md)
- 分支与提交规范：[CONTRIBUTING.md](CONTRIBUTING.md)
- 静态检查：`mvn verify` 会执行 Checkstyle 与 PMD
- 前端检查：`npm run lint` / `npm run format:check`

## 文档

- 产品需求：[mfg-aps-platform-PRD.md](mfg-aps-platform-PRD.md)
- 竞品调研：[开源APS竞品调研报告.md](开源APS竞品调研报告.md)
