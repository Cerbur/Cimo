# Cimo Plan Main

> Cimo 的计划总入口。这里维护全局目标、阶段拆分、当前状态和分步计划索引；具体实现细节沉到对应的 `plan_stepN.md`。

---

## 总目标

从零构建一个 Agent Harness 工具。每个 Step 做最小的事，逐步演进；每个抽象都必须能回答它解决的真实问题、当前必要性和可验证收益。

---

## 当前状态

- 当前阶段：Step 1 - CLI Agent Loop + Anthropic + BashTool
- 当前详细计划：[plan_step1.md](plan_step1.md)
- 计划目录化完成时间：2026-05-09 16:30 CST
- 对应 Git Commit：5e6875f

---

## 阶段索引

| Step | 内容 | 详细计划 | 状态 |
|------|------|----------|------|
| Step 1 | CLI Agent Loop + Anthropic + BashTool | [plan_step1.md](plan_step1.md) | 进行中 |
| Step 2 | 更多工具集，例如 Read / Write / Edit / Glob；CLI 内置指令识别与分发 | [plan_step2.md](plan_step2.md) | 规划中 |
| Step 3 | Session 管理 + 消息历史持久化 | 待创建 | 未开始 |
| Step 4 | Harness 管理层 + REST API | 待创建 | 未开始 |
| Step 5 | Web 前端（独立项目） | 待创建 | 未开始 |
| Step 6 | 安全加固 + Docker 部署 | 待创建 | 未开始 |

---

## 计划文件规则

- `plan_main.md` 只维护全局入口、阶段状态、跨阶段决策索引和分步计划链接。
- `plan_stepN.md` 维护对应阶段的目标、任务拆解、验收标准、执行决策、完成记录和决策记录。
- 当某个 `plan_stepN.md` 过大时，优先按阶段或主题继续拆分，并在本文件保留索引。
- 根目录不再新增 `plan.md`；历史 `plan.md` 已迁移为 [plan_step1.md](plan_step1.md)。

---

## 跨阶段决策索引

| 时间 | 决策 | 来源 |
|------|------|------|
| 2026-05-09 16:30 CST | 计划文件统一收口到 `.plan/`；`plan_main.md` 做总入口，`plan_stepN.md` 承载分步细节；根目录不再维护 `plan.md`。 | 本文件 |
| 2026-05-10 02:15 CST | Step 2 需要预留 CLI 内置指令识别与分发 handler：从 `CliAgentEntry` 当前内联处理 `exit/quit` 的逻辑出发，后续统一承载 `/mcp`、`/compact` 等不应直接进入 Agent Loop 的指令。 | [plan_step2.md](plan_step2.md) |
| 2026-05-10 CST | Step 1 修正 Cimo 一级配置边界：重新引入 `CimoProperties` 承载 `provider`、`debug`、`work-dir`、`agent` 等 Cimo 自身配置，替代 `SpringEnvironmentReader` 的静态 `Environment` 读取；provider-specific 配置仍保留在专属 properties 中。 | [plan_step1.md](plan_step1.md) |
| 2026-05-10 CST | Spring Bean 注入规范：除构造器中存在真实初始化逻辑、派生对象创建或校验逻辑外，统一使用成员变量 `@Autowired` 显式注入；`DefaultAgentLoop` 当前构造器创建 `Client` 属于例外。 | [plan_step1.md](plan_step1.md) |
| 2026-05-11 02:19 CST | Step 1 新增 S1-38 注释补充任务：按 AGENTS.md 注释质量要求，只补 public 类型职责、核心流程、关键约束和状态流转说明；不做机械注释、不改变行为。 | [plan_step1.md](plan_step1.md) |
