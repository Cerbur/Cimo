# Cimo Plan Main

> Cimo 的计划总入口。这里维护全局目标、阶段拆分、当前状态和分步计划索引；具体实现细节沉到对应的 `plan_stepN.md`。

---

## 总目标

从零构建一个 Agent Harness 工具。每个 Step 做最小的事，逐步演进；每个抽象都必须能回答它解决的真实问题、当前必要性和可验证收益。

---

## 当前状态

- 当前阶段：Step 2 - 工具集扩展 + CLI 内置指令
- 当前详细计划：[plan_step2.md](plan_step2.md)
- Step 2 状态：Ready；编码执行前仍需用户明确说「可以开始了」
- Step 1 完成时间：2026-05-11 03:37 CST
- Step 1 对应 Git Commit：24062a7
- Step 1 归档状态：Done，只读归档；除补充完成记录勘误外不得继续更新 todo 或改写计划内容
- 计划目录化完成时间：2026-05-09 16:30 CST
- 对应 Git Commit：5e6875f

---

## 阶段索引

| Step | 内容 | 详细计划 | 状态 |
|------|------|----------|------|
| Step 1 | CLI Agent Loop + Anthropic + BashTool | [plan_step1.md](plan_step1.md) | Done / Archived / Read-only |
| Step 2 | 最小工具集 read / list / search / write / edit / bash；CLI 退出指令识别与分发 | [plan_step2.md](plan_step2.md) | Ready |
| Step 3 | Session 管理 + 消息历史持久化 | 待创建 | 未开始 |
| Step 4 | Harness 管理层 + REST API | 待创建 | 未开始 |
| Step 5 | Web 前端（独立项目） | 待创建 | 未开始 |
| Step 6 | 安全加固 + Docker 部署 | 待创建 | 未开始 |

## 横向计划

| 计划 | 内容 | 详细计划 | 状态 |
|------|------|----------|------|
| QA | 自动化 QA 策略、风险地图、候选测试分层与后续质量门禁 | [plan_qa.md](plan_qa.md) | 规划中 |
| Agent Process | 代理工作流程、Plan 组织规则、执行门禁 | [agent_process.md](agent_process.md) | 生效 |
| Engineering Rules | 长期工程规范、代码约定、架构约束 | [engineering_rules.md](engineering_rules.md) | 生效 |

---

## 计划文件规则

- `plan_main.md` 只维护全局入口、阶段状态、跨阶段决策索引和分步计划链接。
- `plan_stepN.md` 维护对应阶段的目标、任务拆解、验收标准、执行决策、完成记录和决策记录。
- `agent_process.md` 维护代理工作流程、Plan 组织规则、执行门禁和 QA 门禁。
- `engineering_rules.md` 维护长期工程规范，避免 `AGENTS.md` 承载过多细节。
- 新建 `plan_stepN.md` 时优先从 [plan_template.md](plan_template.md) 生成，必须保留 Todo ID、状态、验收标准和 ADR-lite 决策记录。
- 状态为 `Done / Archived / Read-only` 的 step 视为已归档，只允许阅读；除完成记录勘误、commit ID 补全等历史修正外，不得继续更新 todo、追加新范围或改写计划内容。
- 当某个 `plan_stepN.md` 过大时，优先按阶段或主题继续拆分，并在本文件保留索引。
- 根目录不再新增 `plan.md`；历史 `plan.md` 已迁移为 [plan_step1.md](plan_step1.md)。

---

## 跨阶段决策索引

| 时间 | 决策 | 来源 |
|------|------|------|
| 2026-05-09 16:30 CST | 计划文件统一收口到 `.plan/`；`plan_main.md` 做总入口，`plan_stepN.md` 承载分步细节；根目录不再维护 `plan.md`。 | 本文件 |
| 2026-05-10 02:15 CST | Step 2 需要预留 CLI 内置指令识别与分发 handler：从 `CliAgentEntry` 当前内联处理 `exit/quit` 的逻辑出发，后续统一承载 `/mcp`、`/compact` 等不应直接进入 Agent Loop 的指令。 | [plan_step2.md](plan_step2.md) |
| 2026-05-13 CST | Step 2 的 CLI 指令范围按奥卡姆剃刀收窄：S2-T01 只处理当前真实存在的 `exit` / `quit`，`/mcp`、`/compact` 等到对应能力真的落地时再单独设计。 | [plan_step2.md](plan_step2.md) |
| 2026-05-13 CST | Step 2 计划进入 Ready：执行顺序固定为 ToolExecutionContext/schema、文件安全公共逻辑、文件工具、Bash、流式输出、端到端验收；Ready 不等于开始编码，仍需用户明确说「可以开始了」。 | [plan_step2.md](plan_step2.md) |
| 2026-05-15 CST | Step 2 Bash 设计从 allow/ask/deny 权限模型调整为 hard-coded 执行确认模型：单个 `command` 字符串保留真实 shell 复合命令能力；每次执行前展示原始命令，只有用户输入 `y` 才执行，其他输入取消；后续权限分类和确认缓存上移到 runtime/confirmation 层。 | [plan_step2.md](plan_step2.md) |
| 2026-05-10 CST | Step 1 修正 Cimo 一级配置边界：重新引入 `CimoProperties` 承载 `provider`、`debug`、`work-dir`、`agent` 等 Cimo 自身配置，替代 `SpringEnvironmentReader` 的静态 `Environment` 读取；provider-specific 配置仍保留在专属 properties 中。 | [plan_step1.md](plan_step1.md) |
| 2026-05-10 CST | Spring Bean 注入规范：除构造器中存在真实初始化逻辑、派生对象创建或校验逻辑外，统一使用成员变量 `@Autowired` 显式注入；`DefaultAgentLoop` 当前构造器创建 `Client` 属于例外。 | [plan_step1.md](plan_step1.md) |
| 2026-05-11 02:19 CST | Step 1 新增 S1-38 注释补充任务：按 AGENTS.md 注释质量要求，只补 public 类型职责、核心流程、关键约束和状态流转说明；不做机械注释、不改变行为。 | [plan_step1.md](plan_step1.md) |
| 2026-05-11 03:35 CST | 新增跨阶段 QA 计划：先记录契约测试、替身端到端、CLI 黑盒、真实 provider smoke、安全边界、架构约束和 Plan QA 的候选方向；不把普通单测或覆盖率作为默认目标；暂不进入编码。 | [plan_qa.md](plan_qa.md) |
| 2026-05-11 CST | 引入 plan 模板；后续 step 计划必须使用 Todo ID、状态、验收标准和 ADR-lite 记录；Step 1 标记为 Done / Archived / Read-only。 | 本文件 / [plan_template.md](plan_template.md) |
| 2026-05-14 22:16 CST | 拆分代理规则文档：`AGENTS.md` 收敛为入口硬约束和索引；详细流程迁移到 `agent_process.md`；工程规范迁移到 `engineering_rules.md`；现有约束只搬迁不删除。 | [../AGENTS.md](../AGENTS.md) / [agent_process.md](agent_process.md) / [engineering_rules.md](engineering_rules.md) |

---

## 后续规划提醒

> 这里只记录跨阶段提醒，不代表已进入某个 step 的执行范围；创建或迭代后续 `plan_stepN.md` 时，代理需要主动提醒用户评估是否纳入。

- Anthropic thinking block 支持：当前为保证工具回合协议稳定，默认显式关闭 thinking。后续若需要展示 thinking，需要先规划“thinking block 持久化、按原顺序回传 provider、CLI 可选展示/隐藏、真实 provider smoke 验证”这一整条能力，不能只移除 `thinkingDisabled()`。
