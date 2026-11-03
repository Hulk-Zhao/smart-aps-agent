# 贡献指南（衡策 APS）

感谢参与衡策 APS 开源版建设。本指南定义分支模型、提交规范、PR 流程与本地检查要求。

## 1. 分支模型

| 分支 | 用途 | 来源 | 合并目标 | 保护 |
|---|---|---|---|---|
| `main` | 生产发布分支，始终可部署 | - | - | 是 |
| `develop` | 日常集成分支，MVP 功能汇总 | `main` | `main`（发布时） | 是 |
| `feature/{task-no}-{slug}` | 功能开发 | `develop` | `develop` | 否 |
| `hotfix/{version}-{slug}` | 线上紧急修复 | `main` | `main` 与 `develop` | 否 |

命名示例：

- `feature/P0-03-git-workflow`
- `feature/FR-SE-001-timefold-adapter`
- `hotfix/v0.1.1-solver-timeout`

约定：

- 一个分支只对应一个任务或一个缺陷，避免混入无关改动。
- 分支名使用小写英文与连字符，任务号保留原文大小写。
- 合并使用 Squash Merge，保持 `develop` 提交历史线性可读。

## 2. 仓库分支保护（平台设置说明）

> 本节仅说明平台应配置的保护规则，仓库内不做任何平台设置变更；由仓库管理员在托管平台执行。

`main` 与 `develop` 均应启用：

1. 禁止直接 push，所有变更必须通过 PR。
2. 至少 1 名评审通过（`main` 建议 2 名），且评审人不能是提交者本人。
3. 必需状态检查通过后才可合并：
   - `mvn clean verify`（编译 + Checkstyle + PMD）
   - `npm ci && npm run lint && npm run format:check && npm run build`（`aps-frontend`）
4. 分支必须与目标分支保持最新（Require branches to be up to date）。
5. 禁止 force push、禁止删除受保护分支。
6. 管理员同样受规则约束（Do not allow bypassing）。
7. 合并后自动删除源分支（可选）。

`hotfix/*` 合入 `main` 后必须回合并到 `develop`，避免修复丢失。

## 3. 提交信息规范（Conventional Commits）

格式：

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

- `type`：必填，取值见下表。
- `scope`：可选，建议填写模块或领域。
- `subject`：必填，中文或英文均可，不加句号，不超过 100 字符。
- `body`：说明「为什么改」与关键取舍，可多行。
- `footer`：破坏性变更写 `BREAKING CHANGE: 说明`；关联任务写 `Refs: P0-03`。

| type | 含义 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 仅文档 |
| `style` | 不影响逻辑的格式调整 |
| `refactor` | 重构，不改变外部行为 |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `build` | 构建脚本 / 依赖 |
| `ci` | CI 配置 |
| `chore` | 杂项维护 |
| `revert` | 回滚提交 |

推荐 scope：`common`、`domain`、`infra`、`solver`、`service`、`web`、`admin`、`frontend`、`docs`、`build`。

示例：

```
feat(solver): 新增 SolverAdapter 引擎路由
fix(web): 修正订单不存在时的错误码
docs(db): 补充 orders 状态机说明
refactor(service): 拆分排产服务与订单查询服务
```

## 4. 提交前检查

根目录安装提交规范工具（仅首次）：

```bash
npm install
```

校验提交信息：

```bash
# 校验编辑器中的提交信息（配合 git commit 使用）
npm run commitlint

# 校验最近一次提交
npm run commitlint:range
```

可选：将 commitlint 接入本地 `commit-msg` 钩子（由个人本地配置，仓库不强制安装 husky）：

```bash
echo 'npx --no-install commitlint --edit "$1"' > .git/hooks/commit-msg
chmod +x .git/hooks/commit-msg
```

代码检查：

```bash
# 后端（含 Checkstyle / PMD，绑定在 verify 阶段）
mvn clean verify

# 前端
cd aps-frontend
npm ci
npm run lint
npm run format:check
npm run build
```

## 5. PR 流程

1. 从最新 `develop` 拉出 `feature/*` 分支。
2. 完成开发与自测，确保提交信息符合规范。
3. 按 `.github/pull_request_template.md` 填写 PR，关联任务号。
4. 至少 1 名评审通过，且 CI 全绿后 Squash Merge 到 `develop`。
5. 发布时由 `develop` 向 `main` 提 PR；紧急修复走 `hotfix/*` 并回合并。

评审重点关注：

- 模块依赖方向是否被破坏，求解引擎实现是否泄漏到 `aps-service` / `aps-web`。
- 数据库字段与枚举是否与 `docs/db/schema.md` 一致。
- 是否补齐异常处理、日志、Javadoc 与边界校验。
- 是否引入不必要依赖或重复工具类。

## 6. 行为准则

- 保持尊重、就事论事，技术分歧以数据和文档为依据。
- 不提交他人未授权的代码、密钥或受限数据。
- 发现规范缺口时先提 Issue 讨论，再更新本指南与工具配置。
