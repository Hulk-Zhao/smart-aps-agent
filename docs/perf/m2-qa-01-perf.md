# M2-QA-01 排产求解性能压测报告

- 执行人：dev-QA1
- 执行时间：2026-11-04 09:28 – 09:57（Asia/Shanghai）
- 仓库：`d:\tools\smart-aps-agent`
- 结论（2026-11-04 用户裁定）：**移出 M2 门禁，转后续性能专项**；M2 改按演示口径验收。
> 以下为裁定前原始结论，仅存档，不再作为 M2 验收口径。
- 结论：**任务完成（压测与证据链已交付）；性能验收不达标**。

> 一句话：500 订单 / 3000 任务的可行解在默认 60s 内不可得；把时限放宽到 300s 仍然不可行（`-16742hard`）。默认 60s 下返回的 `SOLVED 0hard/0soft` 是**假成功**——实际只排下 2101/3000 个任务，落库 0 行。
>
> 2026-11-04 用户裁定：500 单 60s 已移出 M2 门禁；上述“假成功”与 500 单性能数字均保留为**修复前观测**；修正后的容量上限与 M2 演示口径见 §5A / §5B。

---

## 1. 环境与隔离（未触碰他人实例与库）

| 项 | 值 |
|---|---|
| 实例端口 | **18089**（独立实例，PID 28392；压测完成后已按要求停止，端口已释放） |
| 压测库 | **aps_qa_perf**（独立新建，未写 `aps_local`） |
| 已验收实例 | 18085（PID 28496）**全程未触碰** |
| 制品 | `D:\tools\_aps-localdata\qa-perf\aps-admin-qa.jar`（由 09:28 前的完整 fat jar 复制，`BOOT-INF/lib`=112；未执行 `mvn package`） |
| JVM | `-Xms512m -Xmx2g -G1 -Xlog:gc`，Java 21.0.13+9 |
| 机器 | 8 vCPU / 64GB RAM |
| 安全模式 | `dev`（写端点用 `X-API-Key: dev-write-key`） |
| 数据版本 | 启动时 Flyway 应用 V1–V6，`execution time 00:00.662s` |

未修改任何生产代码；仅新增两个脚本和本报告。仓库内其他会话的未提交改动未触碰、未回滚、未 commit。

---

## 2. 数据集（可复算）

生成脚本：[seed-m2-perf.ps1](file:///d:/tools/smart-aps-agent/scripts/seed-m2-perf.ps1)

- 幂等：按固定 UUID + 精确号段（`RES-CNC-11..30` 等）+ 专属 sandbox `e9000000-0000-0000-0000-000000000401` 清理后重建；`-Clean` 仅清理。不使用任何前缀 `LIKE`。
- 拒绝写入 `aps_local*`（脚本内硬校验）。
- **实测规模**：

| 项目 | 实测值 |
|---|---|
| 订单 | **500**（`CONFIRMED`，交期 2026-11-06 ~ 11-12，优先级 4 档混排，数量 1–20） |
| 工艺路线 | 10 条 ACTIVE，每产品 **6 道工序** |
| 工序节点/依赖边 | 60 / 50（FS 边，含 30min 滞后） |
| 工序模板 | 8 |
| 资源 | **50**（CNC 20 / 热处理 5 / 装配 15 / 质检 5 辅 / 人力 5 辅） |
| 工作日历 | 6（全厂日历周一–周六三班 + 5 个热处理资源专属日历） |
| 停机窗口 | 全厂停机保养 2026-11-07；热处理炉检修 2026-11-09（均已入库） |
| 策略 | 1 策略 + 8 条软规则（权重合计 100） |
| 求解任务行 | 期望 **3000**（500×6）；50 订单对照为 300 |
| 实测任务使用资源 | 300 任务实际占用 **40/50** 个资源，48 条订单链 |

**建库/建数据真实耗时（实测）**：

| 步骤 | 耗时 |
|---|---|
| `createdb aps_qa_perf`（探针库同法实测） | 312 ms |
| Flyway V1–V6（日志） | 0.662 s |
| 应用启动到 Ready（日志） | 5.925 s |
| 数据集脚本（生成 SQL + 执行 + 校验，500 订单） | **479 ms** |
| 库体积 | 11 MB |

---

## 3. 阈值出处与判定口径

**计划文档已规定 500 订单性能目标**：

- [m2-plan.md#L52](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L52)：  
  > 验收口径：M2 不整体套用 MVP 6.4（PRD 1601-1606）。M2 只取其中 **500 订单 <60s、Docker ≤15min** 两项。
- [m2-plan.md#L205](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L205)：  
  > 验收方式：9 类模板可下载/可导入/错误行可定位；500 订单 <60s；`docker compose up` ≤15min；QA-02 输出 P0/P1 缺陷清单。
- [m2-plan.md#L561](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L561)：本卡口径为「500 订单数据；C28 降级；指标」。

**契约文档未规定性能 SLA**（[contract-freeze.md](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md) 中只有默认参数与状态语义）：

- [contract-freeze.md#L283](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L283)：`int timeoutSeconds, // 默认 60`；
- [contract-freeze.md#L1359-L1360](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L1359-L1360)：`status=SOLVED` 时 `tasks` 返回该 sandbox 当前 task 快照。

> 因此：**“500 订单 <60s” 来自计划，不是我自定的**。计划未写明 `hard=0`、`persistedTaskCount=3000`、无降级这三项，它们是我为本次观测补充的可复算判据，**不是计划原文验收线**。

**合格判据（计划目标 + 本次观测口径）**：

1. 计划目标：端到端 `POST /api/v1/solve-tasks` → 终态 **≤ 60s**（m2-plan.md#L52）；
2. 本次观测补充：`bestScore` 硬分 **= 0**（可行解）、`persistedTaskCount` **= 3000** 且库内行数一致、无 `engineFallback`/`TIMEOUT`。

**字段口径**：

- `e2eMs`：本地 POST 发起 → 首次轮询到终态的墙钟耗时（轮询 900ms/次，误差 ±0.9s）；
- `engineDurationMs`：服务端 `metrics.durationMs`（Timefold 内部）；
- `totalDurationMs`：服务端 `metrics.totalDurationMs`（ASSEMBLING→落库完成）；
- `persistApproxMs = totalDurationMs - engineDurationMs`（装配+落库合计，不是纯落库）；
- `heapPeakMb`：本次运行窗口 GC 日志中 **GC 前已用堆最大值**（分配峰值，非存活集）；`heapPeakAfterGcMb` 为 GC 后存活近似；另记录进程 WorkingSet 峰值。

---

## 4. 实测结果

| # | 规模 | 时限/参数 | 终态 | bestScore | e2eMs | 引擎ms | 总ms | 落库ms(近似) | persisted | 库行数 | 堆峰值/存活 | WorkingSet | 降级 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 50 订单/300 任务 | 默认 60s | FAILED | **-34hard**/-351335soft | 62,762 | 61,014 | 61,586 | 572 | 0 | 0 | 425MB/47MB | 738MB | `engineFallback=true, ENGINE_ERROR` |
| 2 | 50 订单/300 任务 | 180s | FAILED | **-4hard**/-179790soft | 181,223 | 180,082 | 180,484 | 402 | 0 | 0 | 448MB/70MB | 776MB | `engineFallback=true, ENGINE_ERROR` |
| 3 | 50 订单/300 任务 | 300s | **SOLVED** | **0hard**/-165370soft | 301,969 | 300,539 | 300,749 | 210 | **300** | **300** | 479MB/101MB | 833MB | 无 |
| 4 | 50 订单/300 任务 | 60s + `moveThreadCount=7` | FAILED | **-31hard**/-343815soft | 61,693 | 60,137 | 60,351 | 214 | 0 | 0 | 736MB/566MB | 1,227MB | `engineFallback=true, ENGINE_ERROR` |
| 5 | 500 订单/3000 任务 | 默认 60s | SOLVED（假） | **0hard/0soft** | 62,283 | 60,156 | 61,783 | 1,627 | **0** | **0** | 449MB/77MB | 809MB | 无 |
| 6 | 500 订单/3000 任务 | 300s | **FAILED** | **-16742hard**/-88147595soft | 303,839 | 301,448 | 303,196 | 1,748 | 0 | 0 | **965MB**/708MB | **1,458MB** | `engineFallback=true, ENGINE_ERROR` |

补充：6 次运行均**未产生 `TIMEOUT` 终态**；引擎到达时间上限后，最终表现为 `FAILED`（#1/#2/#4/#6）或“空结果 SOLVED”（#5）。

---

## 5. 验收判定：**不达标**

> 2026-11-04 用户裁定：500 单 60s 已移出 M2 门禁。本节为**修复前观测记录**，保留原始判定与数据；M2 验收改按 §5B 演示口径。

对照第 3 节判据（500 订单；`E2E ≤ 60s` 为计划目标，`hard=0/persisted=3000/无降级` 为本次观测补充）：

| 判据 | 实测 | 结果 |
|---|---|---|
| E2E ≤ 60s | 62.3s（默认）/ 303.8s（放宽） | 不达标 |
| hard = 0 | 0（假）/ **-16742**（300s） | 不达标 |
| persistedTaskCount = 3000 | **0 / 0** | 不达标 |
| 无 fallback / TIMEOUT | 300s 运行触发 `engineFallback` | 不达标 |

**对照结论（50 → 500）**：是**明显拐点**，不是线性增长。

- 任务数 10 倍（300 → 3000）；
- 构造启发式（CH）完成时间从 **0.55s → 212.24s（约 386 倍）**；
- CH 的 move 评估速率从 **246,296/s → 21,911/s（下降 11.2 倍）**；
- 50 订单在 180s 仍 `-4hard`，300s 才 `0hard`；500 订单即使 300s 仍 `-16742hard`；
- 因此 500 订单在 60s 内出可行解**不具备现实可能性**，300s 也不够。

## 5A. 容量上限（2026-11-04 裁定后复测口径，未重新压测）

> 数据来源：由修复专线在修正后实例实测提供；本次 QA 未重新运行。与首轮实测的差异（终态变为 `TIMEOUT`、硬分不同）来自修复分支行为变化；首轮原始数据保留在 §4/§6，作为**修复前观测**。

| 场景 | 终态 | 硬分 | 构解（CH） | 任务数 | persistedTaskCount |
|---|---|---|---|---|---|
| 500 单 / 60s | **TIMEOUT** | **-3000hard / 0soft**（来源：修复专线 500/60 实测） | 约 **2071–2160 步**（未完成） | expected 3000 / assigned 0 | **0** |
| 500 单 / 300s | **TIMEOUT** | **-6929hard** | **3000 步 ≈ 167.8s（27,706 步/s）** | expected 3000 | **0** |

**容量上限结论**：

- 瓶颈在**构解（CH）**，不是约束评估：3000 任务构解约 **167.8s**；500 单 60s 连构解都未完成（2071–2160 步）。
- **社区版无多线程**：`moveThreadCount` 对结果零影响；不传 / 1 / 7 三次终态一致，耗时 **61.66 / 61.39 / 61.26s**。
- 因此 500 单 60s 不再作为 M2 门禁，转后续性能专项。

## 5B. M2 演示口径证据（2026-11-04 裁定）

> 数据来源：修复专线演示实例；本次 QA 未重新压测。以下为 M2 改用的验收口径。

| 演示场景 | 终态 | 落库行数 | 耗时 | 备注 |
|---|---|---|---|---|
| 8 单 | **SOLVED（3/3 次）** | **48 行**（8×6） | 10.8–12.1s | 三次运行均 SOLVED |
| 50 单 / 60s | **SOLVED** | **300 行**（50×6） | elapsed ≈ **61.3s** | 演示规模可完成 |
| 50 单 / 5s | **TIMEOUT** | **0 行** | 5s 时限 | `-590hard`，`persistedTaskCount=0`，`detail=SOLVER_TIMEOUT` |

M2 验收改按 8 单与 50 单演示口径；500 单 60s 移出 M2 门禁。

---

## 6. 证据

> 标注：以下 §6.1–6.5 均为**首轮实测（修复前）**；与 §5A 修正后数值不同，全部保留，未删改。

### 6.1 500 订单真实响应片段

**默认 60s（taskId=3，假成功）**：

```json
{
  "taskId": 3,
  "status": "SOLVED",
  "phase": "DONE",
  "bestScore": "0hard/0soft",
  "elapsedMs": 61822,
  "metrics": {
    "durationMs": 60156,
    "totalDurationMs": 61783,
    "taskCount": 0,
    "persistedTaskCount": 0,
    "terminationReason": "TIME_LIMIT",
    "engineFallback": null
  },
  "tasks": []
}
```

**300s（taskId=5，真实不可行）**：

```json
{
  "taskId": 5,
  "status": "FAILED",
  "phase": "FAILED",
  "bestScore": "-16742hard/-88147595soft",
  "elapsedMs": 303199,
  "errorCode": "ENGINE_ERROR",
  "metrics": {
    "durationMs": 301448,
    "totalDurationMs": 303196,
    "taskCount": 1638,
    "persistedTaskCount": 0,
    "terminationReason": "TIME_LIMIT",
    "engineFallback": true,
    "engineFallbackReason": "ENGINE_ERROR",
    "fallbackMode": "HARD_ONLY",
    "setupMatrixFallback": true
  },
  "tasks": []
}
```

**50 订单 300s 成功（taskId=4，唯一落库成功的一次）**：

```json
{
  "taskId": 4,
  "status": "SOLVED",
  "bestScore": "0hard/-165370soft",
  "elapsedMs": 300988,
  "metrics": {
    "durationMs": 300539,
    "totalDurationMs": 300749,
    "persistedTaskCount": 300,
    "justificationCount": 90,
    "terminationReason": "TIME_LIMIT",
    "setupMatrixFallback": true
  }
}
```

### 6.2 求解器日志（原始行）

500 订单 / 60s：CH 只排下 2101/3000（实体数 3050 = 3000 TaskPlan + 50 ResourcePlan）：

```
2026-11-04T09:41:44.128 ... Problem scale: genuine entity count (3050), genuine variable count (3050), approximate value count (3169)
2026-11-04T09:42:44.105 WARN ... DefaultConstructionHeuristicPhase terminated early with step count (2101).
2026-11-04T09:42:44.108 ... Construction Heuristic phase (0) ended: time spent (60012), best score (0hard/0soft), move evaluation speed (38565/sec), step total (2101).
2026-11-04T09:42:44.123 ... Solving ended: time spent (60027), best score (0hard/0soft), move evaluation speed (38535/sec), move thread count (NONE).
```

500 订单 / 300s：CH 本身耗时 212s，LS 阶段无法收敛：

```
2026-11-04T09:50:12.727 ... Problem scale: genuine entity count (3050), genuine variable count (3050), approximate value count (3169)
2026-11-04T09:53:44.879 ... Construction Heuristic phase (0) ended: time spent (212240), best score (0hard/0soft), move evaluation speed (21911/sec), step total (3000).
2026-11-04T09:55:13.319 ... Construction Heuristic phase (1) ended: time spent (300680), best score (-16742hard/-88147595soft), move evaluation speed (3135/sec), step total (1638).
2026-11-04T09:55:13.897 ... Solving ended: time spent (301258), best score (-16742hard/-88147595soft), move thread count (NONE).
```

50 订单 / 300s：可行解在 300.5s 才出现：

```
2026-11-04T09:49:49.894 ... Local Search phase (2) ended: time spent (300498), best score (0hard/-165370soft), move evaluation speed (1900/sec), step total (37183).
2026-11-04T09:49:49.901 ... Solving ended: time spent (300505), best score (0hard/-165370soft), move evaluation speed (2252/sec), move thread count (NONE).
```

50 订单 / 60s：LS 跑了 13,426 步仍 `-34hard`：

```
2026-11-04T09:35:40.260 ... Local Search phase (2) ended: time spent (60017), best score (-34hard/-351335soft), move evaluation speed (1960/sec), step total (13426).
```

**`moveThreadCount=7` 无效**（请求已带该参数，日志仍为单线程）：

```
2026-11-04T09:56:34.935 ... Solving ended: time spent (60087), best score (-31hard/-343815soft), move evaluation speed (3757/sec), move thread count (NONE).
```

### 6.3 内存（真实日志行）

500 订单 / 300s 运行窗口（JVM 启动后 1609s，即 09:55 附近），堆分配峰值 965MB：

```
[1609.254s][info][gc] GC(5196) Pause Young (Concurrent Start) (G1 Evacuation Pause) 965M->613M(1555M) 8.527ms
```

同窗口 GC 共 682 次、总停顿 1,771.7ms；GC 后存活约 708MB；进程 WorkingSet 峰值 1,458MB。默认 `-Xmx2g` 尚有余量，但 500 订单已在 0.96GB 分配峰值，1000 订单或并发请求存在 OOM 风险。

### 6.4 库内核对

```text
solve_task:
id|status|score_hard|score_soft|durationMs|totalDurationMs|persistedTaskCount|terminationReason|engineFallback|engineFallbackReason
1|FAILED|-34|-351335|61014|61586||TIME_LIMIT|true|ENGINE_ERROR
2|FAILED|-4|-179790|180082|180484||TIME_LIMIT|true|ENGINE_ERROR
3|SOLVED|0|0|60156|61783|0|TIME_LIMIT||
4|SOLVED|0|-165370|300539|300749|300|TIME_LIMIT||
5|FAILED|-16742|-88147595|301448|303196||TIME_LIMIT|true|ENGINE_ERROR
6|FAILED|-31|-343815|60137|60351||TIME_LIMIT|true|ENGINE_ERROR
```

`task` 表最终只有 300 行（taskId=4 的 50 订单结果）；两次 500 订单运行均为 0 行。

原始产物：
- 结果 JSON：`D:\tools\_aps-localdata\qa-perf\results\*.json`（6 个）
- 应用日志：`D:\tools\_aps-localdata\qa-perf\app-18089.log`
- GC 日志：`D:\tools\_aps-localdata\qa-perf\gc-18089.log`

### 6.5 `SOLVED` 与“落库 0 行”的自相矛盾：时间线与判断（taskId=3，500 单/60s）

> 标注（2026-11-04）：本节为**修复前观测**，保留原始日志与判断。修正后同一路径改为 `TIMEOUT` 且 `detail=SOLVER_TIMEOUT`（见 §5B 第三条）。

**原始日志（09:42:44 前后，无任何 ERROR/WARN/Exception/rollback/persist 行）**：

```
2026-11-04T09:42:44.105+08:00 WARN  [aps-solve-worker] ... DefaultConstructionHeuristicPhase terminated early with step count (2101).
2026-11-04T09:42:44.108+08:00 INFO  [aps-solve-worker] ... Construction Heuristic phase (0) ended: time spent (60012), best score (0hard/0soft), move evaluation speed (38565/sec), step total (2101).
2026-11-04T09:42:44.123+08:00 INFO  [aps-solve-worker] ... Solving ended: time spent (60027), best score (0hard/0soft), phase total (3), move thread count (NONE).
2026-11-04T09:42:44.431+08:00 INFO  [http-nio-18089-exec-1] ... http_request method=GET path=/api/v1/solve-tasks/3 status=200 durationMs=3
```

从 `Solving ended`（.123）到首个终态 GET（.431）共 **308ms**，中间**没有任何落库/异常日志**；响应 `status=SOLVED`、`phase=DONE`、`taskCount=0`、`persistedTaskCount=0`、`justificationCount=1`。库内 `solve_task 3` 同为 0/0；`constraint_justification` 仅 1 行且 `implicated_facts=[]`：

```
3|primaryResourceOnly|primaryResourceOnly|HARD|[]
```

**判断：不是落库抛异常被吞，也不是事务回滚，而是“空解被判可行 + 无完整性断言”。**

1. CH 在 60s 只放置 2101/3000 后被硬性中断，**best solution 仍是初始解**（全部 `startTime=null`）；未排任务没有硬惩罚，初始解即 `0hard/0soft`。
2. [TimefoldModelBuilder.toSolution](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldModelBuilder.java#L127-L163) 的 `assignments` 来自资源上的任务反影子列表 → 为空；`score.isFeasible()` 为真 → 状态写 `SOLVED`。
3. [ScheduleResultWriter](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleResultWriter.java#L81-L99) 先 `deleteTasks(orderIds)`，再遍历空的 `assignments`，`written=0`；[重叠守卫](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleResultWriter.java#L144-L155) 以空的 `insertedTaskIds` 查询，自然无重叠、不抛异常；[归因写入](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleResultWriter.java#L167-L175) 仍写入 1 条空 facts 记录；事务正常提交。
4. [ScheduleService](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java#L342-L359) 只按 `solution.status` 记终态，**没有断言 `assignments.size == 应排任务数`**。

**后果**：`SOLVED` 可与“零落库”共存；且 `deleteTasks` 在写入前执行，若在已成功的 50 订单之后重跑这条路径，会**先静默删除既有 300 行、再写入 0 行**。因此所有基于终态的成功判断目前都不可信。

---

## 7. 根因分析（只定位，不改代码）

> 标注（2026-11-04）：以下为**修复前观测与推断**，保留存档。原“约束评估 O(n²) 为主瓶颈”的推断已被 §5A 的构解实测修正（构解 ≈167.8s 才是主因）；原始数据与结论不删除。

1. **资源不重叠约束是全局两两配对，成本 O(n²)**  
   [ApsConstraintProvider.java](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/constraint/ApsConstraintProvider.java#L62-L67) 用 `factory.forEachUniquePair(TaskPlan.class)` 覆盖**全表所有任务对**，再用 [ConstraintPredicates.resourceOverlap](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/constraint/ConstraintPredicates.java#L24-L47) 过滤。  
   300 任务 = 44,850 对；3000 任务 = 4,498,500 对（100 倍）。每次 move 都触碰该任务与全表的关系，单步成本随 n 线性、总工作量随 n² 增长。日志中 CH 评估速率 246,296/s → 21,911/s（10 倍任务、11 倍变慢）即该结构的直接体现。
2. **多线程求解未接线**  
   [TimefoldSolverAdapter.solverConfig](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldSolverAdapter.java#L225-L241) 只设置了 class/constraint/seed/termination，**没有调用 `withMoveThreadCount(...)`**，`SolveConfig.moveThreadCount` 仅在信封层解析（[SolveEnvelopeMapper.java](file:///d:/tools/smart-aps-agent/aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolveEnvelopeMapper.java#L67-L84)）。8 核机器上 7 个线程全部闲置，日志固定输出 `move thread count (NONE)`。

3. **未排任务没有硬惩罚 → 假成功**  
   500/60s 时 CH 只放置 2101/3000 即被时间上限打断；未放置任务不产生硬分，初始/中断解为 `0hard/0soft`，[TimefoldModelBuilder.toSolution](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldModelBuilder.java#L127-L163) 按 `score.isFeasible()` 判 `SOLVED`；[ScheduleService](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java#L342-L359) 原样返回 SOLVED，最终落库 0 行。  
   结果是“求解成功、库内无任务”，对上层是静默错误。

4. **引擎指标不可用于调优**  
   `stepCount`/`unimprovedStepCount` 恒为 0、`terminationReason` 固定 `TIME_LIMIT`（[TimefoldModelBuilder.java](file:///d:/tools/smart-aps-agent/aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldModelBuilder.java#L155-L160)），即使 `unimprovedSecondsSpentLimit` 生效也无从判断。

5. **时间上限映射语义**：引擎以 `TIME_LIMIT` 结束但解不可行时，API 落 `FAILED + ENGINE_ERROR`，六值状态中的 `TIMEOUT` 从未使用（[ScheduleService.fallbackOrFailed](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java#L393-L400)）。这属于**契约语义待确认**，我未改动契约。

---

## 8. 风险点

| 级别 | 风险 | 依据 |
|---|---|---|
| P0 | 500 订单默认配置**假成功**：SOLVED/0hard/0soft 但 0 行落库 | taskId=3 响应 + `task` 表 0 行 |
| P0 | 空解写入前先 `deleteTasks`，重跑该路径会**静默清空既有任务** | [ScheduleResultWriter.java#L81-L99](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleResultWriter.java#L81-L99) |
| P0 | 500 订单在 300s 内**无法得到可行解**（-16742 hard），目标不可达 | taskId=5 + 求解日志 |
| P1 | O(n²) 资源重叠约束导致规模拐点，50→500 任务 CH 慢 386 倍 | 日志 0.55s vs 212.24s |
| P1 | 8 核只用单线程，算力浪费约 7/8 | `move thread count (NONE)` |
| P2 | 500 订单堆分配峰值 965MB、存活 708MB，`-Xmx2g` 余量有限；更大批量/并发有 OOM 风险 | GC 日志 |
| P2 | 求解指标（steps/termination）失真，无法做参数回归 | metrics 恒 0 / 固定 TIME_LIMIT |
| P2 | 50 订单也要 300s 才可行，任何“60s 级”承诺目前都不成立 | 50@60s `-34hard`，50@180s `-4hard` |

---

## 9. 建议（仅报告，未改生产代码）

1. **优先改资源不重叠约束**：按 `resourceId` 分组后比较同资源内的相邻/成对任务（或使用 list-variable 的 consecutive pair），把全局 450 万对降到“各资源队列内对”，这是数量级收益；改完需复跑本报告 6 个用例。
2. **接通 `moveThreadCount`**：`SolverConfig.withMoveThreadCount(config.moveThreadCount())`，并验证 planning clone 的线程安全；CH 仍单线程，LS 可并行，预计 LS 阶段近线性加速。
3. **为未排任务加硬惩罚**：`startTime == null` 必须产生 `ONE_HARD`，杜绝“空结果 SOLVED”；同时要求 `persistedTaskCount == taskCount` 才能判成功。
4. **修正求解指标**：填真实 `stepCount/unimprovedStepCount`、按实际终止原因写 `terminationReason`；时间上限且不可行时按契约决定是 `TIMEOUT` 还是 `FAILED`，不要 `ENGINE_ERROR` 兜底。
5. **容量与内存**：500 订单建议 `-Xmx4g` 起步并做压测门禁；当前 2g 在 0.96GB 分配峰值下仅够单实例单任务。
6. **短期交付策略**：在约束优化落地前，500 订单应按滚动排产拆分（如 ≤200 订单/次）或放宽到 300s+，并在产品侧明确“非 60s 级”。

---

## 10. 复现方式

```powershell
# 1) 独立库（不改 aps_local）
D:\tools\_aps-toolchain\pgsql\bin\createdb.exe -h 127.0.0.1 -p 55432 -U aps aps_qa_perf

# 2) 独立实例 18089（首次启动由 Flyway 建表）
$env:DB_HOST='127.0.0.1'; $env:DB_PORT='55432'; $env:DB_NAME='aps_qa_perf'
$env:DB_USER='aps'; $env:DB_PASSWORD='aps_dev_pwd'
java -Xms512m -Xmx2g -Xlog:gc:file=gc-18089.log -jar aps-admin-qa.jar `
  --spring.profiles.active=dev --server.port=18089 --aps.security.mode=dev

# 3) 建 500 订单数据集（约 0.5s）
powershell -NoProfile -File scripts\seed-m2-perf.ps1 -OrderCount 500 -DbName aps_qa_perf

# 4) 压测（50 对照 / 500 主测）
powershell -NoProfile -File scripts\run-m2-perf.ps1 -Orders 50  -Label orders-50-default -JavaPid <pid>
powershell -NoProfile -File scripts\run-m2-perf.ps1 -Orders 500 -Label orders-500-default -JavaPid <pid>
powershell -NoProfile -File scripts\run-m2-perf.ps1 -Orders 500 -Label orders-500-t300 -TimeoutSeconds 300 -UnimprovedSecondsSpentLimit 60 -HardLimitSeconds 380 -JavaPid <pid>
```

清理：`powershell -NoProfile -File scripts\seed-m2-perf.ps1 -Clean -DbName aps_qa_perf`（会删除本报告的实测数据，取证后慎用）。

---

## 11. 边界声明

- 未执行 `mvn package/repackage/verify`；仅使用既有完整 fat jar 副本运行。
- 未写 `aps_local`；未触碰 18085（PID 28496）。
- 未修改生产代码、未改契约、未 commit/push。
- 压测期间同仓库其他会话的未提交改动均未触碰。
- 本报告所有数字均来自上述 JSON/日志/SQL 实测；未使用推断值。
