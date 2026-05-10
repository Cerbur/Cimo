# Cimo Step 2 Plan

> Step 2 详细计划：更多工具集（Read / Write / Edit / Glob...）以及 CLI 内置指令识别与分发。全局计划入口见 [plan_main.md](plan_main.md)，计划模板见 [plan_template.md](plan_template.md)。

---

## Step 2：工具集扩展 + CLI 内置指令

### 目标

在 Step 1 跑通 Agent Loop 的基础上，扩展更接近 Codex / Claude Code 的本地开发能力：文件读取、写入、编辑、搜索等工具；同时把 CLI 中不应进入 LLM 的本地指令抽象为独立识别与分发链路。

本阶段最终要让 Agent 可以通过工具读取、搜索并在受控范围内修改工作区文件；CLI 可以识别本地指令，例如 `/mcp`、`/compact`，并交给专门 handler 处理，而不是作为普通自然语言输入提交给 `AgentLoop`；`exit` / `quit` 这类退出指令的行为保持兼容，并纳入同一套指令处理边界。

### 非目标

- 不实现 Session 管理或消息历史持久化；这属于 Step 3。
- 不引入复杂命令框架或 TUI 渲染框架。
- 不重做 provider 抽象；Step 2 只修正必要的工具 schema、流式事件语义和 CLI 渲染边界。
- 不实现完整 MCP 协议；`/mcp` 先作为 CLI 本地指令预留入口，具体行为需要后续单独规划。
- 不把所有输出强制拆成单字动画；如果未来需要展示动画，应放在 CLI 展示层作为可配置效果。

### 当前状态

- Status: Draft
- 当前负责人：用户与代理共同迭代
- 最近更新时间：2026-05-11 CST

### 背景与约束

- 第一性原理：Step 2 的新增能力必须直接服务“本地开发 Agent 能读、搜、改文件，并能区分自然语言输入与本地控制指令”这个真实问题。
- 奥卡姆约束：每个新增抽象都必须证明独立职责、当前必要性和可验证收益；不能为了预想的多 provider、多 entry 或完整命令系统提前堆层。
- 兼容性约束：Step 1 已归档为 Done / Archived / Read-only；Step 2 不能回头改写 Step 1 计划内容，只能在当前计划中承接已完成能力。
- CLI 约束：`exit` / `quit` 默认保持无斜杠兼容；`/mcp`、`/compact` 等本地指令不应进入 LLM。
- Prompt 约束：新增 system prompt、工具引导词、Agent/Step 提示词必须集中维护在 `prompt` 包，入口层、Agent Loop、Client adapter 不直接内联大段提示词。
- Spring 注入约束：除构造器存在真实初始化逻辑、派生对象创建、不可变状态计算或启动期校验逻辑外，Spring Bean 依赖统一使用成员变量上的 `@Autowired` 显式注入。
- 注释约束：新增或修改代码时，类需要补充清晰中文注释；重要业务方法、公共方法、流程编排方法或带关键约束的方法需要补充中文注释；禁止机械复述代码字面含义。

### 允许修改范围

- `src/main/java/ai/cerbur/cimo/`
- `src/test/`
- `src/main/resources/`
- `.plan/plan_step2.md`
- 为了接入 Spring 生命周期、优雅退出、配置绑定、工具注册、CLI 启动流程或运行时资源加载，允许修改 `CimoApplication.java`、`src/main/resources/` 以及 `ai.cerbur.cimo` 包下相关代码。
- 必要时可调整 `build.gradle` 中与 Step 2 测试或工具实现直接相关的依赖，但需要先在计划中记录原因。

### 禁止修改范围

- `.plan/plan_step1.md` 的 todo、范围、设计内容；Step 1 已归档只读。
- 根目录新增 `plan.md`。
- Web / REST / Session 持久化 / Docker / 用户认证相关实现。
- 与 Step 2 无关的包结构重构、命名迁移、格式化大改或资源文件调整。

---

## 待解决问题

### S2-01：CLI 内置指令识别与分发

当前 `CliAgentEntry` 在 REPL 主循环里直接判断退出命令：

```java
if ("exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim())) {
    break;
}
submitInput(line);
```

位置：`src/main/java/ai/cerbur/cimo/entry/CliAgentEntry.java:70-72`

后续需要抽象一个专门识别指令的 handler，用于处理类似 `/mcp`、`/compact` 这样的 CLI 本地指令。

#### 第一性原理

这个抽象解决的真实问题是：CLI 输入并不全是用户要发给 LLM 的自然语言任务；部分输入是本地运行时控制命令，例如退出、查看 MCP 状态、触发上下文压缩等。如果继续把这些判断散落在 `CliAgentEntry` 里，入口层会逐渐承担过多业务分支，且无法复用到未来的其他 entry。

#### 设计约束

- `CliAgentEntry` 只负责读取用户输入、打印事件、调用指令分发或 Agent Loop，不内联不断增长的指令判断。
- 指令 handler 必须能明确返回“已处理 / 未识别 / 需要退出”等结果，避免入口层猜测副作用。
- `/mcp`、`/compact` 这类指令先进入计划，不急于实现；只有存在真实行为和验收标准时再落代码。
- `exit` / `quit` 是否保留无斜杠形式需要在实现前确认兼容策略；默认保持兼容。
- 不引入复杂命令框架；优先用最小接口和少量实现类完成需求。

#### 候选接口方向

```java
public interface CliCommandHandler {
    CliCommandResult handle(String input);
}

public sealed interface CliCommandResult {
    record Handled() extends CliCommandResult {}
    record NotCommand() extends CliCommandResult {}
    record ExitRequested() extends CliCommandResult {}
}
```

> 这只是计划中的候选方向，编码前仍需根据 Step 2 的工具和 session/context 设计一起收口。

### S2-06：流式输出体验增强

当前代码已经有流式输出的基础链路：

```text
Client.chatStream()
  -> DefaultAgentLoop.callClient()
  -> AgentEvent.Response
  -> CliAgentEntry.onEvent()
  -> System.out.print(...)
```

相关位置：

- `src/main/java/ai/cerbur/cimo/client/Client.java`
- `src/main/java/ai/cerbur/cimo/client/anthropic/SpringAiAnthropicClient.java`
- `src/main/java/ai/cerbur/cimo/agent/DefaultAgentLoop.java`
- `src/main/java/ai/cerbur/cimo/entry/CliAgentEntry.java`
- `src/main/java/ai/cerbur/cimo/client/model/StreamEvent.java`
- `src/main/java/ai/cerbur/cimo/client/model/StreamEventType.java`

#### 第一性原理

这个增强解决的真实问题是：CLI Agent 的反馈不应该等模型整段回复完成后才出现。主流 Agent 的交互体验依赖持续的文本增量输出，让用户能即时感知模型正在工作，并且在工具调用前看到模型已经生成的解释或上下文。

当前已有 `Flux<StreamEvent>`、`TEXT_DELTA`、`AgentEvent.Response` 和 `System.out.print(...)`，说明不需要重新设计一套输出架构；重点是保证 provider adapter 真正吐出“新增文本 delta”，并让 CLI 稳定即时渲染。

#### 设计约束

- `StreamEventType.TEXT_DELTA` 的语义必须收口为“本次新增文本”，不能是 provider 返回的累计全文。
- `SpringAiAnthropicClient` 需要验证 Spring AI Anthropic 的 `assistantMessage.getText()` 是增量 chunk 还是累计文本：
  - 如果已经是增量 chunk，只需保持透传。
  - 如果是累计文本，adapter 内部负责 diff，只向上游发新增部分。
- `DefaultAgentLoop` 继续承担两件事：一边 emit 增量内容给 entry 展示，一边聚合完整 assistant turn 写入 `MessageHistory`。
- `CliAgentEntry` 负责即时 flush 和换行边界，避免 response、tool call、tool result 混在同一行。
- 不为了视觉上的“逐字动画”引入复杂渲染抽象。若未来需要模拟逐字输出，应作为 CLI 展示层的可配置效果，而不是改变 `Client` / `AgentLoop` 的核心语义。
- `TOOL_USE_START` / `TOOL_USE_DELTA` / `TOOL_USE_END` 可以先保留事件模型；只有需要展示工具参数生成过程或更精细的 provider 事件时再落实现。

#### 候选实现方向

最小实现路径：

1. 用真实 Anthropic 调用或测试替身确认 `SpringAiAnthropicClient` 当前收到的文本粒度。
2. 保证 `StreamEvent.textDelta(...)` 只承载新增文本。
3. 在 `CliAgentEntry` 的 `AgentEvent.Response` 分支打印后立即 `flush()`。
4. 统一处理 response 与 tool call / tool result 之间的换行，避免终端输出粘连。
5. 为 adapter 的 delta 语义补单元测试，至少覆盖“provider 返回累计文本时不会重复输出”的情况；如果 Spring AI 难以直接构造累计流，则用局部 helper 或测试替身隔离验证。

---

## 任务拆解

| ID | Todo | 状态 | 验收标准 |
|----|------|------|----------|
| S2-T01 | 设计 CLI 内置指令识别与分发 handler，覆盖 `exit` / `quit`、预留 `/mcp`、`/compact` | Draft | `CliAgentEntry` 不再内联不断增长的指令判断；`exit` / `quit` 保持兼容；本地指令不会进入 `AgentLoop`；有单元测试覆盖已处理、未识别、退出请求三类结果 |
| S2-T02 | 定义 Step 2 文件工具范围：Read / Write / Edit / Glob / List 的最小可用集合 | Draft | 每个工具都有第一性原理说明、输入输出边界、是否当前必要的判断；不引入没有独立职责的工具或抽象 |
| S2-T03 | 明确工具安全边界：工作区根目录、路径穿越、防误写、覆盖策略 | Draft | 所有文件工具都只能在工作区根目录内操作；路径穿越被拒绝；写入/覆盖策略有明确错误提示和测试覆盖 |
| S2-T04 | 明确工具 schema 与 provider adapter 的边界，避免重新引入无职责 `ToolSpec` | Draft | 工具 schema 从 `Tool` 或明确的工具描述结构出发；provider adapter 只做 provider 协议转换；不会出现无法证明职责的中间抽象 |
| S2-T05 | 为文件工具补充单元测试和最小端到端验证 | Draft | 单元测试覆盖成功、失败、边界路径、覆盖策略；最小端到端能验证 Agent 通过工具读取/搜索/修改工作区文件 |
| S2-T06 | 增强 CLI 流式输出体验：保证 `TEXT_DELTA` 为新增文本、response 即时 flush、tool 输出换行边界稳定 | Draft | `TEXT_DELTA` 不重复输出累计全文；CLI 能即时 flush；response、tool call、tool result 不粘连；有测试或替身验证 delta 语义 |

---

## 验收方式

- 必跑命令：
  - `./gradlew test`
- 涉及 CLI 指令时：
  - 启动 `./gradlew bootRun` 后验证 `exit` / `quit` 仍可退出。
  - 验证 `/mcp`、`/compact` 等已规划本地指令不会作为普通自然语言进入 `AgentLoop`；若暂未实现具体行为，应返回明确的本地提示。
- 涉及文件工具时：
  - 验证 Read / Write / Edit / Glob / List 的成功路径与失败路径。
  - 验证路径穿越、工作区外访问、防误写或覆盖策略。
- 涉及流式输出时：
  - 验证 `TEXT_DELTA` 是新增文本而非累计全文。
  - 验证 CLI 输出即时 flush，且 response 与 tool 输出之间不会粘连。
- 不通过处理：
  - 若验证失败，不得把相关 Todo 标记为 Done；必须在“执行记录”中记录失败命令、失败现象和下一步处理。

> 验收命令与具体验收场景会在 Step 2 后续规划中继续细化。

---

## 风险与回滚

| 风险 | 触发条件 | 缓解/回滚 |
|------|----------|-----------|
| CLI 指令抽象过重 | 为 `/mcp`、`/compact` 预留时引入完整命令框架或过多层级 | 回退到最小 handler + result 模型，只保留当前可验证行为 |
| 文件工具误操作 | Write/Edit 允许工作区外路径、路径穿越或无保护覆盖 | 所有路径统一做工作区归一化校验；写入和覆盖策略先测试后实现 |
| Provider schema 边界漂移 | 为了适配 Anthropic 重新引入无职责 `ToolSpec` | 保持核心 `Tool` 为事实来源，adapter 只负责协议转换；若必须新增结构，先记录职责和收益 |
| 流式输出重复 | provider 返回累计文本但上游当增量渲染 | adapter 内部 diff 或隔离 helper 测试，确保上游只看到新增 delta |

---

## 关键决策记录（ADR-lite）

| 时间 | 决策 | 原因 | 备选方案 | 影响范围 | Git Commit |
|------|------|------|----------|----------|------------|
| 2026-05-10 02:15 CST | Step 2 记录 CLI 内置指令抽象需求：`CliAgentEntry.java:70-72` 当前内联处理 `exit/quit`，后续抽象 command handler 统一处理退出以及 `/mcp`、`/compact` 等本地指令；暂不进入编码。 | CLI 输入存在本地控制指令，不能全部进入 LLM；入口层不应持续膨胀。 | 继续在 `CliAgentEntry` 内联判断；引入完整命令框架。 | `entry` 包、未来 CLI 指令扩展。 | 924dd94 |
| 2026-05-10 02:47 CST | Step 2 记录流式输出体验增强：当前已有 `Client.chatStream()` 到 `System.out.print(...)` 的链路，后续重点是保证 provider adapter 的 `TEXT_DELTA` 语义为新增文本，并在 CLI 层做好即时 flush 与 response/tool 输出换行边界；不引入复杂 TUI 或强制逐字动画。 | 交互式 Agent 需要即时反馈；现有链路已足够，问题集中在 delta 语义和 CLI 输出边界。 | 重做渲染层；强制逐字动画；重做 provider 抽象。 | `client`、`agent`、`entry` 包。 | 924dd94 |
| 2026-05-11 CST | Step 2 按 plan 模板重写，所有 todo 使用稳定 ID，并补充状态、验收标准、允许/禁止修改范围、ADR-lite、风险与回滚。 | 让计划成为后续编码执行契约，减少 Agent 自行扩范围或丢失 todo 的风险。 | 继续使用自由文本 todo。 | `.plan/plan_step2.md`。 | 未提交 |

---

## 执行记录

| 时间 | Todo ID | 操作 | 验证结果 | Git Commit |
|------|---------|------|----------|------------|

---

## 完成记录

| 完成时间 | Plan | Git Commit |
|---------|------|------------|
