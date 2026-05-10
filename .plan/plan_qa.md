# Cimo QA Plan

> 跨阶段 QA 计划：先沉淀 Cimo 的质量保障思路、风险地图和候选自动化方案；等对 QA 边界更清楚、且用户明确说「可以开始了」后，再拆成具体编码任务执行。

---

## 目标

为 Cimo 建立一套能随着 Agent Harness 演进而增长的自动化 QA 策略。这个计划不把“多写单测”当作默认答案，而是先识别项目真正容易出错的地方，再选择最小、可验证、能长期降低误判的检查方式。

当前阶段的 QA 重点不是追求覆盖率数字，而是回答三个问题：

- 哪些行为一旦坏掉，用户会立刻感知或后续 Agent 会误判？
- 哪些边界仅靠普通单测很难证明，需要契约测试、端到端验证或真实 smoke test？
- 哪些检查应该自动化，哪些更适合保留为手动验收记录？

---

## 当前理解

### 普通单测的局限

复杂业务里，普通单测如果只验证 getter、简单分支或实现细节，确实容易变成维护负担。Cimo 的核心复杂度也不在“某个方法返回了什么”，而在多层协议和状态流转是否一致：

- LLM provider 返回的 `tool_use` 是否能被 Cimo 保真接住。
- `tool_use.id` 与后续 `tool_result.toolUseId` 是否能正确对应。
- CLI 输出是否既让用户看懂，又不泄露 debug-only 原始协议。
- provider 配置失败是否在启动期暴露，而不是拖到第一次调用才失败。
- 后续文件工具是否被限制在工作区和明确能力边界内。

因此，QA 设计应优先覆盖“协议契约、状态流转、边界安全、真实交互可观察行为”，而不是优先追求细碎的类级覆盖率。

### 当前已有基础

- Gradle + JUnit 平台已经可用。
- 已存在基础测试：工具、配置、ClientFactory、CLI entry、Spring context。
- Step 1 已有真实 CLI 验收记录，验证 `./gradlew bootRun`、Anthropic 调用、`bash echo hello` tool call、tool result 和最终回复完整走通。
- Step 2 已计划补充文件工具测试和流式 delta 语义测试，本 QA 计划不替代这些局部任务，只提供横向策略。

---

## QA 分层候选

### 1. 契约测试：优先级最高

契约测试用于锁住 Cimo 自己定义的核心协议，避免 provider adapter、Agent Loop、Tool 之间的边界被无意改坏。

候选覆盖点：

- `ChatMessage` / `ContentBlock` 的 block-based 消息模型。
- assistant `ToolUse` 到 user `ToolResult` 的 ID 对应关系。
- `ClientRequest.systemPrompt` 独立传入，不被塞进普通消息历史。
- `StreamEventType.TEXT_DELTA` 语义必须是“新增文本”，不能是累计全文。
- Agent Loop 收到 tool call 后必须先执行工具，再把 tool result 回传给 client 获取最终回复。

第一性原理：

这些测试保护的是 Cimo 的内部协议，而不是某个实现细节。只要协议不变，底层 provider、工具实现和 CLI 展示可以演进；协议一旦破坏，复杂 Agent 行为会出现很难排查的错位。

### 2. 端到端替身测试：高价值、低成本

使用 fake `Client` 和 fake `Tool` 跑完整 Agent Loop，不依赖真实网络和 API key。

候选场景：

- 用户输入自然语言。
- fake client 第一次返回 text delta + tool use。
- fake tool 返回成功结果。
- fake client 第二次基于 tool result 返回最终回复。
- 断言事件顺序、消息历史、工具调用参数和最终输出。

第一性原理：

这类测试比孤立单测更贴近 Agent Harness 的真实风险，同时避免真实 LLM 的不稳定性、费用和网络依赖。

### 3. CLI 黑盒测试：验证用户可观察行为

验证入口层能正确处理输入、事件打印、flush、换行、退出指令和未来内置命令。

候选场景：

- `exit` / `quit` 兼容退出。
- tool call 和 tool result 不与 response 文本粘连。
- `cimo.debug=false` 时不打印原始 JSON 协议。
- `cimo.debug=true` 时输出必要诊断信息，但 api key 必须脱敏。

第一性原理：

CLI 是当前产品的主要用户界面。用户真正感知的是终端输出，而不是内部对象是否漂亮；因此要保留一层面向输出文本的 QA。

### 4. 真实 provider smoke test：可选、默认跳过

真实 Anthropic 调用只适合作为显式开启的 smoke test，不适合作为每次本地必跑测试。

候选策略：

- 默认跳过，只有显式参数和必要环境变量存在时运行。
- 验证 Step 1 核心路径：`通过bash输出hello` 能触发 `bash echo hello`，最终回复包含工具结果。
- 失败时明确区分：网络/API/model 行为问题，还是 Cimo 协议问题。

第一性原理：

真实 provider 测试能发现 fake 测试发现不了的 adapter 和模型行为变化，但它天然不稳定、有成本、依赖外部环境，所以只能做 smoke，不做主防线。

### 5. 安全边界测试：Step 2 起逐步增强

文件工具出现后，QA 要重点验证工作区边界和破坏性行为控制。

候选覆盖点：

- 路径穿越必须拒绝。
- 默认不覆盖已有文件，除非工具 schema 和用户意图明确允许。
- Edit 工具必须验证目标片段唯一性或给出清晰失败。
- Glob/List 不能越过工作区根目录。
- 错误信息要能帮助 LLM 后续修正，而不是只返回模糊失败。

第一性原理：

文件工具会从“输出文本”进入“修改用户工作区”。一旦边界不清，后续 Agent 能力越强，误伤成本越高。

### 6. 架构约束测试：后置考虑

当包结构稳定后，可以考虑引入 ArchUnit 或类似检查。

候选约束：

- `entry` 层不直接依赖 provider adapter。
- provider adapter 不执行工具。
- `config` 包只负责配置绑定，不维护工具注册或业务装配。
- 大段 prompt 只能从 `prompt` 包暴露，入口层和 Agent Loop 不内联。

第一性原理：

架构测试适合保护已经稳定的边界，不适合在边界仍快速探索时过早加入。过早加入会把计划阶段的候选设计误固化成负担。

### 7. Plan QA：流程本身也可被检查

Cimo 的开发流程强调按 `.plan/` 行动，因此计划文件也可以被自动化检查。

候选覆盖点：

- 根目录不重新出现 `plan.md`。
- `plan_main.md` 链接当前 step 和横向计划。
- 完成记录包含完成时间和 Git Commit。
- 新增计划任务有明确目标、验收标准和当前状态。

第一性原理：

这个项目明确把 plan 当作工程事实来源之一。自动化检查计划结构，可以降低后续代理把任务写散、漏记决策或绕过计划的概率。

---

## 候选任务池

> 这里只记录候选，不代表立即执行。进入编码前需要由用户确认优先级和范围。

- [ ] QA-01 梳理现有测试清单：按单元、契约、替身端到端、CLI 黑盒、真实 smoke 分类，而不是只按文件名列举。
- [ ] QA-02 为 `DefaultAgentLoop` 设计 fake client + fake tool 的端到端替身测试方案。
- [ ] QA-03 为 provider adapter 的 `TEXT_DELTA` 语义设计契约测试，避免累计文本重复输出。
- [ ] QA-04 为 CLI 输出设计黑盒测试矩阵：debug on/off、tool call/result、换行边界、退出指令。
- [ ] QA-05 为真实 Anthropic smoke test 设计默认跳过机制和运行条件。
- [ ] QA-06 为 Step 2 文件工具定义安全边界测试矩阵，并与 `plan_step2.md` 的 S2-05 保持一致。
- [ ] QA-07 评估是否引入 Spotless、ArchUnit 或自定义 Gradle QA task；只有收益明确时再落地。
- [ ] QA-08 设计 `.plan/` 结构检查，覆盖计划入口、完成记录和根目录 plan 文件约束。

---

## 与其他 Plan 的关系

- `plan_step1.md`：已经包含 Step 1 的配置、CLI 输出、真实交互等局部测试与验收记录；本文件只抽象横向 QA 策略。
- `plan_step2.md`：S2-05 是文件工具的具体测试任务，S2-06 包含流式输出测试要求；本文件引用这些方向，但不覆盖或替换它们。
- 后续 Step：当 Session、Harness API、Web 前端、安全部署出现后，本文件继续补充对应 QA 分层。

如果本文件中的候选 todo 与某个 step plan 的 todo 出现职责冲突，应先暂停并由用户决定保留、移除或拆分，不由代理自行合并。

---

## 决策记录

| 时间 | 决策 | Git Commit |
|------|------|------------|
| 2026-05-11 03:35 CST | 新增跨阶段 QA 计划：先记录契约测试、替身端到端、CLI 黑盒、真实 provider smoke、安全边界、架构约束和 Plan QA 的候选方向；不把普通单测或覆盖率作为默认目标；暂不进入编码。 | 24062a7 |

---

## 完成记录

| 完成时间 | Plan | Git Commit |
|---------|------|------------|
