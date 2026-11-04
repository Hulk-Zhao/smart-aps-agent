# M3-QA-03 安全扫描与加固核查

- 核查日期：2026-11-04
- 范围：`aps-service` / `aps-web` / `aps-infrastructure` / `aps-admin`（`aps-frontend/**` 不在本次范围）
- 方式：只读静态核查 + 交叉比对既有实测结论；**未启动/停止 18085，未触碰 `aps_local`，未执行 `package`/整仓 `verify`，未做 git 写操作**
- 说明：除“跨租户导出”为前置任务已实测外，其余均为静态核查，未做实例复现

## 结论

| 等级 | 数量 | 摘要 |
| --- | --- | --- |
| P0 | 2 | 默认配置即可无认证/伪造管理员；已确认跨租户导出泄露（修复在工作区未提交） |
| P1 | 4 | 52 个写方法（实际已实现 44 个）无权限注解；登录租户固定 DEMO；dev 密钥=ADMIN 且审计身份可伪造；WebSocket 无认证可订阅任意任务 |
| P2 | 8 | 残余租户谓词缺口 4 处、审计脱敏边界、错误信息透传、Swagger 匿名、sort/限流一致性 |

**总体判断**：当前代码的“多租户隔离”对 MyBatis Mapper 有效，但**所有不走 Mapper 的查询必须逐条自证**；鉴权层只做了“认证”，**授权几乎未落地面**；默认部署配置（off/dev + 公开默认密钥）比代码缺陷更危险。

---

## 一、租户隔离全面核查

### 1.1 已确认泄露（与修复状态）

| 项 | 证据 | SQL 片段 | 状态 |
| --- | --- | --- | --- |
| 客户导出 | `aps-service/.../importexport/EntityDataExporter.java:118` | 修复前 `select ... from customer where deleted = false order by code`；当前工作区已为 `where tenant_id = ? and deleted = false` | **已确认（历史实测）**；修复已在工作区、**未提交** |
| 供应商导出 | 同文件 `:156` | 同上，当前已带 `tenant_id = ?` | **已确认（历史实测）**；修复已落地未提交 |

实测结论（前置任务提供）：租户 A 调 `GET /api/v1/customers/export` 能看到租户 B 的客户；根因是 `JdbcTemplate` 不受 MyBatis 租户拦截器覆盖。修复后需用“租户 A token / 租户 B 种子数据”回归验证导出文件仅含 A 数据。

复现请求（修复前 / 修复后回归同一命令）：

```bash
# 使用租户 A 的 JWT；修复前响应文件包含租户 B 的 code/name
curl -s -H "Authorization: Bearer $TENANT_A_JWT" \
  "http://127.0.0.1:18085/api/v1/customers/export" -o customers.xlsx
```

### 1.2 疑似泄露

| 项 | 证据 | 说明 | 等级 |
| --- | --- | --- | --- |
| 资源编码翻译 | `EntityDataExporter.java:275` | `select code from resource where id = ?`，**无 `tenant_id`**；若模板/工艺引用了跨租户资源主键，会把对方资源编码导出 | P2 |
| 求解完成更新 | `aps-infrastructure/.../schedule/ScheduleRepository.java:228` | `UPDATE solve_task ... WHERE id = ?`，无 `tenant_id`（调用方持有本租户 taskId，暂不可跨租户利用） | P2 |
| 归因清理 | `ScheduleRepository.java:269` | `DELETE FROM constraint_justification ... taskIds`，无 `tenant_id`（taskIds 来自本租户删除结果） | P2 |
| 导入任务状态/终态 | `aps-service/.../importexport/ImportJobRepository.java:99/111/130/152` | `status/markRunning/finishSuccess/finishFailed` 均按 `id` 查询/更新；上层的 `find(tenantId, jobId)` 已先校验租户，但 `cancel` 失败后回读 `status(jobId)` 仍可探测他租户作业状态（UUID 难枚举） | P2 |
| 角色/用户挂接 | `aps-infrastructure/.../rbac/RbacRepository.java:194/196/207/209` | `user_role` / `role_permission` 为无 `tenant_id` 的关联表；依赖上游 `userMapper/roleMapper.selectById`（MyBatis 已追加租户）先校验 | P2 |

> 以上 4 类“缺谓词”当前均**不可直接跨租户利用**，但属于纵深防御缺口；`resourceCodeOf` 是唯一可能把对方数据带出的读。

### 1.3 已隔离清单（非 MyBatis 数据访问，逐条）

| 文件 | 关键行 | 访问方式 | 租户谓词 | 结论 |
| --- | --- | --- | --- | --- |
| `EntityDataExporter.java` | 118 / 156 | `JdbcTemplate.queryForList` | 有（工作区修复） | 已隔离（未提交） |
| `ImportJobRepository.java` | 55 / 70 / 85 / 167 | insert / query / cancel | 有 | 已隔离 |
| `CustomerImportHandler.java` | 53 | upsert | SQL 内含 `tenant_id` | 已隔离 |
| `SupplierImportHandler.java` | 55 | upsert | 有 | 已隔离 |
| `MaterialImportHandler.java` | 56 | upsert | 有 | 已隔离 |
| `StrategyImportHandler.java` | 63 | upsert | 有 | 已隔离 |
| `OperationTemplateImportHandler.java` | 68 / 92 | 查重 / 查资源 | 有 | 已隔离 |
| `AuditLogRepository.java` | 95 / 136 / 142 | insert / count / page | 有 | 已隔离 |
| `NotificationOutboxRepository.java` | 51–197 | 全部读写 | 有 | 已隔离 |
| `OrderTaskFreezeRepository.java` | 47 | update | 有 | 已隔离 |
| `PlanCompareRepository.java` | 44 | query | 有 | 已隔离 |
| `PlanVersionRepository.java` | 48–280 | 全部读写 | 有 | 已隔离 |
| `SandboxRepository.java` | 45–194 | 全部读写 | 有 | 已隔离 |
| `RbacRepository.java` | 40–239 | 全部读 | 有 | 已隔离 |
| `ScheduleRepository.java` | 74–596（除 228/269） | 全部读写 | 有 | 已隔离 |
| `SolveTaskRepository.java` | 73–206 | 全部读写 | 有 | 已隔离 |
| `ConstraintJustificationRepository.java` | 60 / 104 | query / update | 有 | 已隔离 |
| `TaskAdjustmentRepository.java` | 89–413 | 全部读写 | 有 | 已隔离 |
| `IncrementalSolveRepository.java` | 37–101 | 全部读写 | 有 | 已隔离 |
| `BomMapper.java` / `MaterialMapper.java` / `OrderMapper.java` / `ResourceMapper.java` / `RoutingNodeReferenceMapper.java` / `ConstraintDslMapper.java` | 见各文件 `@Select` | MyBatis 注解 SQL | **由 `TenantLineInnerInterceptor` 自动追加** | 已隔离（见 4.1 约束） |

补充结论：全仓主代码**未发现 `EntityManager`/`@PersistenceContext`/`NamedParameterJdbcTemplate`**；**未发现生产代码直接读文件/JSON 作为业务数据源**（`RealDbEnvironment` 为测试支撑，不在本次修改范围）。

---

## 二、鉴权面核查

### 2.1 机制现状（决定“无注解端点”的实际暴露）

| 事实 | 证据 |
| --- | --- |
| 未配置 `aps.security.mode` 且无 `prod` profile → 认证模式 **off（全放行）** | `ApiSecurityFilter.java:229-242` |
| 非 `/api/v1/**` 路径、`/health`、`/auth/login`、OPTIONS 直接跳过安全过滤器 | `ApiSecurityFilter.java:92-96` |
| dev 模式：读请求无凭据即放行；写请求需凭据；`X-API-Key` 命中写/读默认密钥即 `adminEquivalent=true` | `ApiSecurityFilter.java:117-126, 151-171` |
| prod 模式：只校验 JWT + 必需角色，不做接口级权限 | `ApiSecurityFilter.java:111-116` |
| 端点**无 `@RequiresPermission` 注解即通过**；裸注解仅要求已认证 | `PermissionInterceptor.java:38-52` |
| 登录成功**无条件追加 `APS_USER` 角色** | `AuthController.java:57-67` |
| 登录接口被过滤器跳过 → `TenantContext` 回落默认租户 DEMO | `ApiSecurityFilter.java:94` + `TenantContext.java:34-37` + `AuthService.java:86` |

### 2.2 已有权限注解（17 个端点）

`MrpController`（`MD_READ` ×2）、`PlanDispatchController`（`SCHEDULE_PUBLISH` ×2）、`AuditLogController`（`SYS_AUDIT`）、`MeController`（裸）、`PermissionController`（裸）、`RoleController`（`SYS_USER` ×1、`SYS_ROLE` ×2）、`UserController`（`SYS_USER` ×3）、`SandboxController`（`SS_READ` ×2、`SS_WRITE`、`SCHEDULE_PUBLISH`）。

### 2.3 无权限注解的写端点（52 个方法；其中 6 个占位、2 个只读计算，**实际已实现写端点 44 个**；prod = 任意登录用户，dev = 任意写密钥/JWT）

| 控制器 | 端点（文件:行） |
| --- | --- |
| Order | `POST /api/v1/orders`（`OrderController.java:112`）；`PUT /orders/{orderNo}`（:126）、`/confirm`（:140）、`/freeze`（:155）、`/priority`（:170）、`/release`（:187） |
| SolveTask | `POST /solve-tasks/incremental`（`SolveTaskController.java:75`）、`POST /solve-tasks`（:94）、`POST /{id}/cancel`（:120） |
| Gantt | `POST /tasks/{id}/adjust`（`GanttContractController.java:51`）、`.../undo`（:67）、`.../redo`（:79） |
| Master data | `POST /boms`（`BomController.java:77`）、`POST /boms/{id}/activate`（:113）、`DELETE /boms/{bomId}`（:125）；`POST /calendars`（`CalendarController.java:85`）、`PUT /{id}`（:98）、`DELETE /{id}`（:111）；`POST /materials`（`MaterialController.java:79`）、`PUT /{code}`（:104）、`DELETE /{code}`（:117）；`POST /resources`（`ResourceController.java:83`）、`PUT /{code}`（:108）、`DELETE /{code}`（:121）；`POST /routings`（`RoutingController.java:112`）、`PUT /{id}/graph`（:139）、`POST /{id}/activate`（:158）、`DELETE /{id}`（:170）；`POST /operation-templates`（`OperationTemplateController.java:91`）、`PUT /{id}`（:104）、`DELETE /{id}`（:118） |
| Strategy / DSL | `POST /strategies`（`StrategyController.java:86`）、`PUT /{id}`（:99）、`DELETE /{id}`（:112）、`POST /{id}/clone`（:126）、`POST /strategies/import`（:153）；`POST /constraint-dsls`（`ConstraintDslController.java:58`）、`/{id}/activate`（:96）、`/{id}/rollback`（:108） |
| Plan | `POST /plans/{id}/publish`（`PlanController.java:75`）、`POST /plans/{id}/rollback`（:93）、`POST /plans/compare`（`PlanCompareController.java:53`） |
| Import/Export | `POST /{entity}/import`（`ImportExportController.java:106`）、`POST /{entity}/import/jobs/{jobId}/cancel`（:159） |
| 其他写 | `PUT /api/v1/inventory`（`InventoryController.java:52`，已实现）；`POST /api/v1/resources/{code}/maintenance`（`MaterialsContractController.java:61`，占位）；`POST /api/v1/orders/atp`（`OrdersContractController.java:34`，占位） |
| 占位（当前 501/占位） | `POST /api/v1/agent/events`（`SolveContractController.java:32`）、`/agent/nl-to-dsl`（:44）、`/agent/whatif`（:68）；`PUT /api/v1/configs`（`SystemContractController.java:43`） |

> 其中 `POST /plans/compare` 与 `POST /constraint-dsls/validate` 为只读计算，但仍无注解。

### 2.4 无权限注解的读端点（34 个方法 + `/plans/compare` 的 4 个非 POST 占位）

`Order`（`GET /orders` :78、`/{orderNo}` :100）、`SolveTask`（`/{id}` :108、`/{id}/justifications` :132）、`Gantt`（`/tasks/{id}/adjustments` :93）、`MaterialsContract`（`/calendars/{id}/available` :46）、`SolveContract`（`/agent/explain/{taskId}` :56）、`SystemContract`（`/configs` :31）、`OrderGantt`（`/gantt/order` :51）、`ResourceGantt`（`/gantt/resource` :51）、`ImportExport`（模板 :78、作业状态 :144、导出 :174）、`Bom`（列表 :62、详情 :89、树 :101）、`Calendar`（:56/:73）、`Material`（:62/:91）、`Resource`（:66/:95）、`Routing`（:79/:99/:126）、`Plan`（:58/:107）、`OperationTemplate`（:62/:79）、`ConstraintDsl`（:71/:84）、`Strategy`（:57/:74/:140）。

暴露差异：
- `off`/本地默认：**全部匿名可读**；
- `dev`：**全部 GET 匿名可读**（`ApiSecurityFilter.java:187`）；
- `prod`：需 JWT，但任意 `APS_USER` 均可读（无接口权限）。

### 2.5 公开端点与白名单

| 端点 | 证据 | 判定 |
| --- | --- | --- |
| `POST /api/v1/auth/login` | `ApiSecurityFilter.java:94`、`AuthController.java:57` | 设计公开；有限流（100 次/分钟/IP/租户键）+ 5 次失败锁定 |
| `GET /api/v1/health` | `ApiSecurityFilter.java:94` | 设计公开 |
| `/ws/solve-tasks/**` | `SolveProgressWebSocketConfiguration.java:24-28` | **非 `/api` 前缀 → 完全绕过认证**（见 P1-4） |
| `/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**` | `ApiSecurityFilter.java:94` | 非 `/api` 前缀 → 永远匿名（见 P2-7） |

### 2.6 dev `X-API-Key` 暴露面

- 默认写密钥 `dev-write-key` / 读密钥 `dev-read-key` 硬编码在 `ApiSecurityProperties.java:16-17`，并随源码/文档分发；命中写密钥即 `adminEquivalent=true`（`ApiSecurityFilter.java:155-158`），**绕过全部 `@RequiresPermission`**。
- 命中读密钥同样是 `adminEquivalent=true`（:165），因此可读 `SYS_AUDIT` 等受限 GET。
- dev 下 `X-User-Id` 决定审计操作人（`ApiSecurityFilter.java:179, 198-207`），可伪造审计身份。
- 限流键包含客户端可控的 `X-Tenant-Id`（`RateLimitFilter.java:116-132`），轮换该头可放大请求额度。

---

## 三、敏感输出核查

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 审计脱敏键覆盖 | **覆盖要求的 6 个键**：`password/passwordhash/token/accesstoken/secret/apikey`，不区分大小写 | `AuditValues.java:21-22, 73-74` |
| 递归脱敏 | 仅递归 `Map`/`List`；`Set`、数组、JSON 字符串、POJO 不处理 | `AuditValues.java:54-70` |
| 审计入口 | `AuditLogService` 的 `record*` 均调用 `AuditValues.redact` | `AuditLogService.java:47-51, 66-68, 80` |
| 直写审计（绕过脱敏） | 8 处直接调 `auditLogRepository.record(...)`，传原始 Map | `OrderService.java:339/382/435`、`PlanDispatchService.java:121`、`SandboxService.java:178`、`TaskAdjustService.java:663` |
| 日志明文口令/token/密钥 | **未发现**：全仓未检索到打印 password/token/secret/apiKey 的日志 | 检索 `LOG*.info/debug/warn/error` + 敏感词 |
| 异常回显 | 未处理异常不回显栈；但 `BizException(4xx)` 直接把 `message` 返回客户端 | `RestExceptionHandler.java:66`；导入错误列表会存异常文本（`StrategyService.java:458`、`ImportJobService.java:223`） |
| 用户实体序列化 | `UserView` 不含 `passwordHash`；账号快照也不含口令 | `UserService.java:243-260` |
| 登录请求体 | 校验失败仅回字段+消息，不回显原值；解析失败不回显 body | `RestExceptionHandler` 相关 handler |

结论：**脱敏实现本身达标**，但存在“绕过点”（直写 repository）和“键覆盖边界”（`secretKey`、`refreshToken`、`authorization`、`credential`、`privateKey` 未覆盖）。当前实际快照未发现这四类键，属 P2 加固项。

---

## 四、注入与序列化面

### 4.1 SQL 注入

- `JdbcTemplate` 全部使用 `?` 占位；`placeholders(n)` 只生成 `?`（`ScheduleRepository.java:599-601`），**未发现把用户输入拼进 SQL**。
- `AuditLogRepository` 的 `appendEquals(column, ...)` 列名由调用方字面量传入（`audit_log` 查询处），非用户输入。
- `SandboxRepository` 的 `ORDER BY` 方向来自 `SandboxService` 受限解析（仅 `createdAt`），非原始字符串。
- **两处 MyBatis `${}`**（字符串替换，非参数化）：
  - `OperationTemplateMapper.xml:120` `ORDER BY ${sortColumn} ...`
  - `RoutingMapper.xml:75` 同类 `${sortColumn}`
  - 来源均为 `SortSpec` 白名单常量映射（`OperationTemplateService.resolveSort` / `RoutingService.resolveSort`），当前**不可注入**；但若未来有调用方直接把请求参数塞入该字段即成注入点。
- `StrategyService` 使用 `wrapper.last("ORDER BY " + column ...)`，`column` 来自固定白名单 Map（`StrategyService.java:923-929`），当前**不可注入**。
- `@Select` 注解 SQL（14 处，见 1.3）均为参数化 + MyBatis 租户拦截器处理；前提是**这些 Mapper 调用始终带租户上下文**（HTTP 由过滤器设置，异步由 `ImportJobService`/`SolveTaskLifecycleService` 显式设置）。

### 4.2 sort / order by 白名单一致性

- 仅 **MRP suggestions** 明确“拒绝非空 `sort`”（C34）；其余列表端点接受 `sort`，分别用 `switch`/Map 白名单解析，未知字段多为静默回退或抛 400，**未发现直接拼接**。
- 结论：安全；但接口契约不一致（同一 `PageQuery.sort` 在不同端点行为不同），建议统一为“非空白名单字段 → 400”。

### 4.3 序列化

- 未发现 Jackson 多态反序列化（`activateDefaultTyping`/`@JsonTypeInfo` 均无）。
- 导入上传有文件大小与行数上限（`ImportLimits`）；仍建议补 OOXML 压缩比/解压上限，防 zip bomb/内存耗尽（P2，非本次必改）。

---

## 五、风险清单（P0/P1/P2 汇总）

| 等级 | 编号 | 风险 | 证据 | 可复现 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- | --- |
| P0 | P0-1 | 默认配置无认证 / JWT 可伪造 | `ApiSecurityFilter.java:229-242`（无 prod 即 off）；`ApiSecurityProperties.java:16-18`（默认密钥）；`application.yml` 无 `aps.security.*`；`docker-compose.yml:195`（默认 `dev`）、`:219-220`（注入的是 `APS_JWT_SECRET`，注释明确“后端尚未读取”） | 本地无 profile 启动 → 任意写；prod 下用公开默认密钥签 `APS_USER` token | 未认证全量读写 / 远程冒充管理员 | 显式 `aps.security.mode`；prod 下 secret==默认值或 `<32B` 时启动失败；compose 注入 `APS_SECURITY_JWT_SECRET`；dev 密钥改为必填环境变量 |
| P0 | P0-2 | 跨租户导出泄露 | `EntityDataExporter.java:118/156`（历史无谓词，现工作区已修）、`:275`（仍缺） | 租户 A token 调 `/customers/export` | 泄露他租户客户/供应商主数据 | 提交 `customer/supplier` 修复；补 `:275` 的 `tenant_id`；导出端点加 `@RequiresPermission` |
| P1 | P1-1 | 写端点系统性缺授权 | 2.3 清单：52 个写方法（实际已实现写 44 个）；`PermissionInterceptor.java:44-46` | 任意 `APS_USER`（登录即获得）调用任一写端点 | 任意登录用户可改主数据/策略/计划/求解/导入 | 全量补 `@RequiresPermission`；拦截器对写方法默认 fail-closed；补端点-权限矩阵测试 |
| P1 | P1-2 | 登录租户固定 DEMO | `ApiSecurityFilter.java:94`（登录被跳过）+ `TenantContext.java:34-37` + `AuthService.java:86` | 任意登录响应 token 的 tenant 恒为 DEMO | 多租户用户无法登录；租户隔离被“单租户假设”掩盖 | 登录请求显式解析受信租户（域名/租户码白名单），或在过滤器中为登录设置租户 |
| P1 | P1-3 | dev 密钥=ADMIN 且默认值公开 | `ApiSecurityProperties.java:16-17`；`ApiSecurityFilter.java:155-165`；审计身份 `X-User-Id`（:179/198-207） | `curl -X POST -H "X-API-Key: dev-write-key" ...` | 任意人可写；审计可归因伪造 | dev 密钥必须环境变量注入；prod 禁用 API Key/`X-User-Id`；审计身份只取 JWT subject |
| P1 | P1-4 | WebSocket 无认证跨任务订阅 | `SolveProgressWebSocketConfiguration.java:24-28`（`/ws/**`、`allowedOriginPatterns("*")`）；`SolveProgressSubscriptionListener.java:57-75` | 连 `ws://host/ws/solve-tasks/1`，`SUBSCRIBE /topic/solve-tasks/{id}` 即收快照 | 匿名读取默认租户求解进度/错误信息；可跨站 WS 劫持 | STOMP `ChannelInterceptor` 校验 JWT；按租户/任务授权订阅；限制 Origin |
| P2 | P2-1 | 导出资源编码缺租户谓词 | `EntityDataExporter.java:275` | 构造跨租户 `primary_resource_id` 的模板后导出 | 泄露他租户资源编码 | 加 `tenant_id` |
| P2 | P2-2 | 求解完成/归因清理缺租户谓词 | `ScheduleRepository.java:228/269` | 需已知他租户 taskId（全局自增，可枚举） | 跨租户更新/删除的纵深风险 | WHERE 增加 `tenant_id` |
| P2 | P2-3 | 导入作业状态/终态缺租户谓词 | `ImportJobRepository.java:99/111/130/152` | `cancel` 失败后回读 `status` | 探测他租户作业状态（UUID 难枚举） | 全部加 `tenant_id` |
| P2 | P2-4 | 关联表替换无租户谓词 | `RbacRepository.java:194-210` | 依赖上游校验，暂无直接入口 | 上游若漏校验即跨租户改角色 | 上游断言 + repository 注释/校验 |
| P2 | P2-5 | 审计脱敏边界与绕过点 | `AuditValues.java:21-22/54-70`；8 处直写（见 3） | 未来快照含 `secretKey/refreshToken` 即明文入库 | 密钥/令牌泄露 | 扩键（`secretkey/refreshtoken/authorization/credential/privatekey`）；支持 Set/数组/JSON 字符串；直写统一走 `AuditValues` |
| P2 | P2-6 | 4xx 错误信息透传 | `RestExceptionHandler.java:66`；`StrategyService.java:458`；`ImportJobService.java:223` | 触发受控异常/导入失败 | 暴露内部标识、SQL/表名等 | 4xx 只回稳定错误码+安全文案；详情仅入日志 |
| P2 | P2-7 | Swagger/Actuator 匿名 | `ApiSecurityFilter.java:92-96` | `GET /v3/api-docs`、`/swagger-ui/index.html` | 匿名获取全量接口契约 | prod 关闭 Springdoc/仅内网暴露；把文档路径纳入鉴权 |
| P2 | P2-8 | sort 契约不一致 / 限流可绕过 / 上传压缩比 | 见 4.2、4.3；`RateLimitFilter.java:116-132` | 轮换 `X-Tenant-Id` 放大额度 | 契约漂移、暴力破解缓解被削弱、zip bomb | 统一 sort 拒绝策略；限流键去掉客户端租户头；补解压上限 |

---

## 六、必须立刻修（短名单）

1. **P0-1（最高优先）**：`application.yml` 显式 `aps.security.mode: ${APS_SECURITY_MODE:prod}`；新增启动校验：prod 下 `secret == DEFAULT_JWT_SECRET` 或长度 `<32` 直接失败；`docker-compose.yml` 改注入 `APS_SECURITY_JWT_SECRET`（当前 `APS_JWT_SECRET` 后端根本不读）；dev 密钥禁止内置默认值。
   - 理由：这是唯一能“零交互拿到管理员”的路径，且当前部署默认就是 dev/off。
2. **P0-2**：提交 `EntityDataExporter` 的 customer/supplier 修复；补 `:275` 的 `tenant_id`；`/{entity}/export` 增加权限注解。
   - 理由：唯一已被实测证实的跨租户数据泄露。
3. **P1-1**：给 2.3 的 44 个已实现写端点补权限注解（占位端点先声明权限，避免上线即裸奔），并把 `PermissionInterceptor` 改为“无注解的写方法默认拒绝”。
   - 理由：当前“有账号就能改全部业务数据”，权限体系形同虚设。
4. **P1-2 / P1-4**：登录租户解析落地方案；STOMP 订阅加 JWT 与任务归属校验。
   - 理由：前者决定多租户是否可用，后者是**完全匿名**的跨任务数据通道。

> 其余 P2 建议随对应模块迭代修复；本次未改动任何业务代码（遵守“只写 `docs/qa/m3-security-scan.md`”及“不碰他人文件”边界）。

---

## 七、后续跟进与已知限制（W5 修复批追加）

- **通用导入权限粒度**：`POST /api/v1/{entity}/import`（含 `/import/{entity}` 别名）与导入任务取消端点统一使用 `MD_WRITE`，因此订单导入、策略导入同样只需 `MD_WRITE`。M3 接受该粗粒度口径；按 `entity` 动态鉴权（订单→`OM_WRITE`、策略→`SS_WRITE`）留作后续迭代。
- **读端点未加读权限**：`GET /api/v1/{entity}/export` 等读端点未加 `*_READ` 权限注解，与本报告已有结论“读端点普遍无接口权限”一致，不改变现状。
