# Cimo Step 2 Plan

> Step 2 详细计划：更多工具集（Read / Write / Edit / Glob...）以及 CLI 内置指令识别与分发。全局计划入口见 [plan_main.md](plan_main.md)。

---

## Step 2：工具集扩展 + CLI 内置指令

### 目标

在 Step 1 跑通 Agent Loop 的基础上，扩展更接近 Codex / Claude Code 的本地开发能力：文件读取、写入、编辑、搜索等工具；同时把 CLI 中不应进入 LLM 的本地指令抽象为独立识别与分发链路。

**验证标准**：

- Agent 可以通过工具读取、搜索并在受控范围内修改工作区文件。
- CLI 可以识别本地指令，例如 `/mcp`、`/compact`，并交给专门 handler 处理，而不是作为普通自然语言输入提交给 `AgentLoop`。
- `exit` / `quit` 这类退出指令的行为保持兼容，并纳入同一套指令处理边界。

---

## 待解决问题

### S2-01 CLI 内置指令识别与分发

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

### S2-06 流式输出体验增强

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

#### 暂不做

- 不引入独立 TUI 渲染框架。
- 不把所有 chunk 强行拆成单字并加延迟，除非后续明确需要一个可配置的展示动画。
- 不在 Step 2 同时重做 provider 抽象；只修正流式事件语义和 CLI 渲染边界。

---

## 任务拆解

- [ ] S2-01 设计 CLI 内置指令识别与分发 handler，覆盖 `exit` / `quit`、预留 `/mcp`、`/compact`
- [ ] S2-02 定义 Step 2 文件工具范围：Read / Write / Edit / Glob / List 的最小可用集合
- [ ] S2-03 明确工具安全边界：工作区根目录、路径穿越、防误写、覆盖策略
- [ ] S2-04 明确工具 schema 与 provider adapter 的边界，避免重新引入无职责 `ToolSpec`
- [ ] S2-05 为文件工具补充单元测试和最小端到端验证
- [ ] S2-06 增强 CLI 流式输出体验：保证 `TEXT_DELTA` 为新增文本、response 即时 flush、tool 输出换行边界稳定

---

## 决策记录

| 时间 | 决策 | Git Commit |
|------|------|------------|
| 2026-05-10 02:15 CST | Step 2 记录 CLI 内置指令抽象需求：`CliAgentEntry.java:70-72` 当前内联处理 `exit/quit`，后续抽象 command handler 统一处理退出以及 `/mcp`、`/compact` 等本地指令；暂不进入编码。 | 924dd94 |
| 2026-05-10 02:47 CST | Step 2 记录流式输出体验增强：当前已有 `Client.chatStream()` 到 `System.out.print(...)` 的链路，后续重点是保证 provider adapter 的 `TEXT_DELTA` 语义为新增文本，并在 CLI 层做好即时 flush 与 response/tool 输出换行边界；不引入复杂 TUI 或强制逐字动画。 | 924dd94 |

---

## 完成记录

| 完成时间 | Plan | Git Commit |
|---------|------|------------|
