# Cimo Step 1 Plan

> Step 1 详细计划：CLI Agent Loop + Anthropic + BashTool。全局计划入口见 [plan_main.md](plan_main.md)。

---

## 整体路线图

```
Step 1: CLI Agent Loop + Anthropic + BashTool  ← 我们现在在这里
Step 2: 更多工具集（Read / Write / Edit / Glob...）
Step 3: Session 管理 + 消息历史持久化
Step 4: Harness 管理层 + REST API
Step 5: Web 前端（独立项目）
Step 6: 安全加固 + Docker 部署
```

---

## Step 1：CLI Agent Loop + Anthropic + BashTool

### 目标

搭好所有抽象层，用**真实的 Anthropic API 调用 + 真实的 Bash 工具执行**跑通完整的 Agent Loop。

**验证标准**：输入 `通过bash输出hello` → Agent 调 Claude → Claude 返回 bash echo hello → 执行并输出结果 → 在 CLI 看到完整交互。

```
用户输入 ──→  CliAgentEntry  ──→  AgentLoop  ──→  Anthropic  ──→ tool_use bash
 (stdin)         (entry)            (agent)        (client)          │
                                                                      ▼
                                                                  BashTool
                                                                  echo hello
                                                                      │
用户 ◄── 打印最终回复 ◄── 结果回 LLM ◄── tool_result ◄──────────────┘
```

### Step 1 做哪些

| 做 | 不做 |
|---|------|
| 全部接口抽象 | 任何 Web/HTTP |
| CLI 入口（JLine 交互式 REPL + 事件打印） | 消息历史持久化 |
| Spring AI Anthropic starter 集成（真实 API 调用） | Session 管理 |
| BashTool 实现（Step 1 仅支持 echo） | 多会话 |
| 完整 Agent Loop（LLM → tool_use → 执行 → 结果回传 → 最终回复） | 用户认证 |
| Spring Boot 启动即进入 CLI | Docker |

### 包结构

```
ai.cerbur.cimo
├── CimoApplication.java
│
├── entry                         ← 入口层：输入来源适配
│   ├── AgentEntry.java               — 接口：start / submitInput / shutdown
│   ├── CliAgentEntry.java            — CLI 实现（JLine REPL → 事件打印到 stdout）
│   └── event/
│       ├── AgentEvent.java           — 事件密封类
│       └── AgentEventHandler.java    — 消费者接口
│
├── agent                          ← Agent 核心：编排 LLM + Tool
│   ├── AgentLoop.java                — 接口：processInput / start / shutdown
│   ├── DefaultAgentLoop.java         — 实现：LLM → tool_use → 执行 → 结果回传 → LLM
│   ├── AgentContext.java             — 上下文
│   └── history/
│       └── MessageHistory.java       — 消息历史（内存，不做持久化）
│
├── prompt                         ← 提示词集中维护
│   └── CimoPrompts.java              — Step 1 system prompt；后续 prompt 常量/模板统一从这里扩展
│
├── tool                           ← 工具定义 + 实现
│   ├── Tool.java                    — 接口
│   ├── ToolResult.java              — 结果封装
│   ├── registry/
│   │   └── ToolRegistry.java        — Harness/Agent 可见的工具注册表（收集可用 Tool）
│   └── impl/
│       └── BashTool.java            — Bash 工具（Step 1 唯一工具，仅支持 echo）
│
├── client                         ← LLM Provider 抽象
│   ├── Client.java                  — 接口：chatStream
│   ├── ClientFactory.java           — 工厂：读取配置创建
│   ├── model/
│   │   ├── ChatMessage.java         — block-based 消息模型（含 Text / ToolUse / ToolResult）
│   │   ├── ChatRole.java            — 角色枚举（user, assistant）
│   │   └── StreamEvent.java         — 流式事件
│   ├── anthropic/
│   │   └── SpringAiAnthropicClient.java — Spring AI Anthropic starter 封装
│   └── openai/
│       └── OpenAiClient.java        — 占位
│
└── config
    ├── AnthropicProperties.java      — Anthropic provider 专属配置
    └── OpenAiProperties.java         — OpenAI provider 专属配置（后续 Step 启用）
```

> 2026-05-09 修正：`CimoProperties` 当前没有独立职责，倾向删除。`cimo.provider`、`cimo.work-dir`、`cimo.agent.max-tool-rounds` 这些少量运行参数优先由真正使用它们的组件通过 `@Value` 或更窄的 properties 绑定读取；只有当多个组件共享同一组稳定配置且能证明聚合收益时，才重新引入全局 properties。

### 关键接口

```java
// ==================== entry ====================

public interface AgentEntry {
    void start();
    void submitInput(String input);
    void shutdown();
}

public sealed interface AgentEvent {
    record Thinking(String message)       extends AgentEvent {}
    record ToolCall(String toolName, JsonNode args) extends AgentEvent {}
    record ToolResult(String toolName, String result) extends AgentEvent {}
    record Response(String content)       extends AgentEvent {}
    record Error(String message)          extends AgentEvent {}
    record StatusChange(AgentState state) extends AgentEvent {}
}

public enum AgentState {
    INITIALIZING,
    RUNNING,
    WAITING_FOR_TOOL,
    COMPLETED,
    ERROR,
    SHUTDOWN
}

public interface AgentEventHandler {
    void onEvent(AgentEvent event);
}

// ==================== agent ====================

public interface AgentLoop {
    void start(AgentContext context, AgentEventHandler handler);
    void processInput(String userInput);
    void shutdown();
}

public record AgentContext(
    String workingDirectory,
    String systemPrompt,
    ToolRegistry toolRegistry,
    int maxToolRounds
) {}

// ==================== tool ====================

public interface Tool {
    String getName();
    String getDescription();
    JsonNode getParameterSchema();
    ToolResult execute(JsonNode arguments);
}

public record ToolResult(
    boolean success, String output, String error, Integer exitCode
) {}

// ==================== client ====================

// ChatMessage: block-based 统一消息模型
// - 一条消息可以包含多个 content block，例如 text + tool_use
// - tool_result 通过 toolUseId 精确对应前一次 assistant tool_use 的 id
public record ChatMessage(
    ChatRole role,
    List<ContentBlock> content
) {}

public enum ChatRole { USER, ASSISTANT }

public sealed interface ContentBlock {
    record Text(String text) implements ContentBlock {}
    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {}
    record ToolResult(String toolUseId, String content, boolean isError) implements ContentBlock {}
}

public record StreamEvent(
    StreamEventType type,
    String content,
    JsonNode toolCall   // tool_use 完整 block（含 id, name, input）
) {}

public enum StreamEventType {
    TEXT_START, TEXT_DELTA, TEXT_END,
    TOOL_USE_START, TOOL_USE_DELTA, TOOL_USE_END,
    COMPLETE, ERROR
}

public record ClientRequest(
    String systemPrompt,
    List<ChatMessage> messages,
    List<Tool> tools
) {}

public interface Client {
    Flux<StreamEvent> chatStream(
        ClientRequest request
    );
}

// ClientFactory 先识别 provider，再通过 Spring AI Anthropic 模型库手动创建选中的 provider client
// Agent Loop 与 tool_result 协议由 Cimo 自己掌控：
// Spring AI 不负责内部工具执行，不吞掉 tool_use/tool_result 细节
@Component
public class ClientFactory {
    public Client createClient() { /* 通过 Spring AI ChatClient 构建 */ }
}
```

### 配置（application.yaml）

```yaml
cimo:
  provider: anthropic
  work-dir: ${user.dir}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    model: claude-sonnet-4-20250514
  agent:
    max-tool-rounds: 5
  tool:
    bash:
      timeout-seconds: 30
```

### System Prompt（Step 1）

Step 1 使用一个很窄的 system prompt，目标是稳定触发 `bash` 工具并避免模型越过当前能力边界。

维护要求：

- 不在 `CliAgentEntry`、`DefaultAgentLoop` 或 `Client` 适配层内联大段 prompt。
- 新增统一包 `ai.cerbur.cimo.prompt`，Step 1 先放 `CimoPrompts.STEP_1_SYSTEM_PROMPT`。
- 后续 Step 的 system prompt、工具提示词、多 Agent 提示词都从该包扩展；如果提示词开始需要变量，再演进为模板/工厂方法。

```text
You are Cimo, a minimal CLI agent running inside a controlled local harness.

Your job is to help the user by deciding whether to answer directly or call an available tool.
In this step, the only available tool is `bash`, and it only supports the `echo` command.

When the user asks you to output text through bash, call the `bash` tool with command `echo`
and pass the text as arguments. Do not invent unavailable tools, do not claim to execute
commands you did not call, and do not request commands outside the current tool schema.

After a tool result is returned, summarize the result briefly in the user's language.
If the user asks for anything outside the current Step 1 capability, explain that this
minimal version only supports echo through bash for now.
```

### Step 1 执行决策

- **消息模型**：使用 block-based `ChatMessage`，一条消息包含 `List<ContentBlock>`，明确支持 `Text`、`ToolUse`、`ToolResult`。
- **System Prompt**：不把 system prompt 塞进 `ChatMessage`；通过 `ClientRequest.systemPrompt` 显式传入，由 Anthropic adapter 映射到 provider 原生 system 字段；提示词内容统一由 `ai.cerbur.cimo.prompt` 包维护，入口层只引用。
- **Anthropic 接入**：使用 Spring AI Anthropic 模型库承接底层 SDK 接入，但不使用 provider starter 自动配置提前创建 client；Agent Loop、tool 调用、`tool_result` 回传协议由 Cimo 自己掌控。
- **Tool Schema 转换边界**：不保留独立 `ToolSpec` 类。当前工具 schema 只在 provider adapter 暴露给 Spring AI/Anthropic 时使用，应由 `SpringAiAnthropicClient` 内部转换为 `ToolCallback/ToolDefinition`；除非后续出现多个 provider 共享同一套稳定 schema 组装逻辑，否则不提前抽象。决策时间：2026-05-09 15:49 CST，Git Commit: 4c1c015。
- **Provider 配置边界**：Anthropic、OpenAI 等 LLM 的 `apiKey`、`model`、`baseUrl` 不放在总配置类里聚合。启动时先读取 `cimo.provider`，再由 provider-specific config / factory 选择对应 LLM adapter 和配置对象；`provider=anthropic` 时只绑定 Anthropic 相关配置，后续加入 OpenAI 时不能要求总配置类同时持有 `anthropic` 和 `openai` 字段。
- **Provider Client 创建边界**：LLM provider 的选择必须发生在 provider client bean 创建之前，而不是启动期先生成所有 provider 再由 `ClientFactory` 选择其一。未被 `cimo.provider` 选中的 provider 不应初始化、不应校验必填配置、不应创建 SDK client 或潜在网络连接。实现上可优先使用 Spring 条件化 Bean（如按 `cimo.provider` 暴露唯一 `Client` / provider adapter），如果 Spring Bean 生命周期让链路变复杂，则完全退回普通工厂模式，在 `createClient()` 中按已识别 provider 手动构造唯一需要的 client。决策时间：2026-05-09 15:55 CST，Git Commit: 4c1c015。
- **Tool 注册边界**：`config` 包只负责把 `application.yaml` 识别并绑定成 properties bean，不维护工具注册、工具列表或工具装配逻辑。工具注册属于 harness/agent 层面的能力边界：Step 1 可先由 `tool.registry.ToolRegistry` 收集可用 `Tool`，后续若引入独立 Harness 管理层，则把注册/选择/暴露工具的逻辑迁移到 harness 包，再由 agent 层按上下文引用注册结果。
- **CimoProperties 删除方向**：当前 `CimoProperties` 同时承载 provider、work-dir、agent、tool 等配置，但这些字段没有形成一个稳定的业务对象，只是把不相关的参数临时装进同一个 record。按第一性原理，它没有独立职责；按奥卡姆剃刀，应删除。`cimo.provider` 应由 `ClientFactory` 或 provider 选择配置读取；`cimo.work-dir` 应由创建 `AgentContext` 的入口/上下文构建处读取；`cimo.agent.max-tool-rounds` 应由 Agent Loop 或 AgentContext 构建处从 `application.yaml` 注入，不在 `CimoProperties` 构造器里写默认对象；`cimo.tool.bash.timeout-seconds` 可由 `BashTool` 或极窄的 `BashToolProperties` 读取。
- **BashTool 能力边界**：Step 1 支持哪些命令不是运行时配置能力，而是 `BashTool` 自身实现和 schema 暴露的一部分。`allowed-commands` 不应出现在 `CimoProperties` 或 `application.yaml` 中，否则配置可以绕过当前 Step 的能力边界。Step 1 固定只支持 `echo`；后续扩展命令时，必须通过修改 `BashTool` 的解析、校验、schema 和测试来显式扩大能力。
- **CLI 形态**：做成类似 Codex / Claude Code 的启动即交互体验：`./gradlew bootRun` 后进入 `>` 提示符，用户直接输入自然语言；底层用 JLine 读取输入并接入 Spring 生命周期，不采用 Spring Shell 的 `ask ...` 命令模式，也不使用裸 `Scanner`。
- **CLI 模式确认**：当前方向明确使用 CLI/JLine REPL 模式作为 Step 1 入口，不再沿用 Spring Shell 命令模式；文档、依赖和入口实现都应围绕“启动即进入自然语言 REPL”收口。决策时间：2026-05-09 16:00 CST，Git Commit: 未提交。
- **Bash 安全边界**：Step 1 的白名单只开放 `echo`；更复杂的命令、参数解析、路径隔离、命令注入防护和沙箱策略全部放到后续安全 Step 中迭代。
- **BashTool 参数结构**：Step 1 不接收整条 shell 字符串，只接收结构化参数，例如 `{ "command": "echo", "args": ["hello"] }`，执行时由 `BashTool` 组装为受控的 `echo` 调用。
- **AgentState 命名**：统一使用 `INITIALIZING / RUNNING / WAITING_FOR_TOOL / COMPLETED / ERROR / SHUTDOWN`。

### Agent Loop 完整流程

```
                          ┌──────────────────────────────────────────────────┐
                          │                 Agent Loop                      │
                          │                                                  │
  ┌─────────┐   submit    ┌──────────┐  chatStream   ┌──────────┐          │
  │   CLI   │───input────►│ AgentLoop│──────────────►│  Client  │          │
  │  Entry  │             │          │                │(Anthropic)│          │
  │ (stdin) │◄───events──│          │◄───────────────│          │          │
  └─────────┘             └────┬─────┘   stream       └──────────┘          │
                               │          events                            │
                               │                                            │
                     ┌─────────┴─────────┐                                  │
                     │   响应解析器       │                                  │
                     │  ┌──────────────┐ │                                  │
                     │  │ content_block│ │                                  │
                     │  │  start/delta │ │                                  │
                     │  └──────┬───────┘ │                                  │
                     │         │         │                                  │
                     │    ┌────┴────┐    │                                  │
                     │    │ text?   │    │                                  │
                     │    └────┬────┘    │                                  │
                     │         │         │                                  │
                     │    ┌────┴────┐    │                                  │
                     │    │tool_use?│    │                                  │
                     │    └────┬────┘    │                                  │
                     └─────────┼─────────┘                                  │
                               │                                            │
                    tool_use ──┘                                            │
                               │                                            │
                         ┌─────▼──────┐                                    │
                         │ ToolRegistry│ 获取工具实例                        │
                         │ .getTool() │                                    │
                         └─────┬──────┘                                    │
                               │                                            │
                         ┌─────▼──────┐                                    │
                         │ BashTool   │ ◄── 如果是 bash 命令               │
                         │ .execute() │                                    │
                         └─────┬──────┘                                    │
                               │                                            │
                         ┌─────▼──────┐                                    │
                         │ tool_result│ 构建 tool_result block              │
                         │ 消息构建    │ 追加到 MessageHistory              │
                         └─────┬──────┘                                    │
                               │                                            │
                               └──→ 回到步骤 3，再次请求 LLM ──────────────┘
                                                             ┌──────────────┐
                                                             │ 终止条件：    │
                                          LLM 返回 text ────►│ end_turn     │
                                                             │ 或 max_rounds│
                                                             │ 或 error     │
                                                             └──────────────┘
                                                                  │
                                                          AgentEvent.Response
                                                                  │
                                                             ┌────▼────┐
                                                             │  CLI     │
                                                             │  打印    │
                                                             └─────────┘
```

**步骤说明：**

```
1. CliAgentEntry 通过 JLine REPL 接收自然语言输入 → AgentLoop.processInput("通过bash输出hello")
2. AgentLoop 构造 ClientRequest（system prompt + ChatMessage 列表 + tools）
3. Client.chatStream(request) → 请求 Anthropic
4. 解析流式响应：
   a. content_block_delta (text) → AgentEvent.Response (流式打印)
   b. content_block_start (tool_use) → stop text stream，记录 tool_use_id
5. ToolRegistry.getTool("bash") → BashTool.execute({command: "echo", args: ["hello"]})
6. 构建 tool_result 消息，toolUseId 沿用步骤 4b 记录的 tool_use_id → AgentEvent.ToolResult
7. AgentLoop 将 tool_result（含 toolUseId）追加入消息历史
8. Client.chatStream(updatedMessages, ...) → 再次请求 Anthropic
9. LLM 返回最终 text → AgentEvent.Response → 打印
10. 回到步骤 1，等待下一轮输入
```

### 交互效果

```
$ ./gradlew bootRun

  ╔══════════════════════════════════════════╗
  ║   Cimo Agent                             ║
  ║   Type 'exit' to quit                    ║
  ╚══════════════════════════════════════════╝

  > 通过bash输出hello
  🤔 Thinking...
  🔧 Tool: bash echo hello
  📋 Result: hello
  🤖 已通过 bash 命令输出了 hello。

  > exit
  Bye!
```

### Step 1 检查清单

- [x] S1-01 所有包和接口定义完毕
- [x] S1-02 明确 Anthropic 集成方式：Spring AI 管依赖和配置入口，Cimo 自己掌控 Agent Loop 与 tool_result 协议
- [x] S1-03 CliAgentEntry（JLine 交互式 REPL + 事件输出）
- [x] S1-04 AgentEvent 模型（sealed class）
- [x] S1-05 AgentState 枚举定义（INITIALIZING / RUNNING / WAITING_FOR_TOOL / COMPLETED / ERROR / SHUTDOWN）
- [x] S1-06 ChatMessage 改为 block-based 模型（Text / ToolUse / ToolResult）
- [x] S1-07 System prompt 通过 ClientRequest 显式传入
- [x] S1-08 SpringAiAnthropicClient 流式调用 + tool_use 响应解析
- [x] S1-09 StreamEvent 支持 text_delta / tool_use_start / tool_input_delta / message_stop 等最小事件
- [x] S1-10 tool_use_id 在消息历史中保存，并用于后续 tool_result 回传
- [x] S1-11 BashTool（安全限制：超时 + 白名单仅 echo）
- [x] S1-12 ToolRegistry（Spring 自动收集 Tool Bean）
- [x] S1-13 删除无引用的 `ToolSpec`：按第一性原理和奥卡姆剃刀，当前 schema 转换职责属于 provider adapter；`ToolSpec` 无独立职责且没有代码引用，进入编码阶段后删除文件（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [x] S1-14 DefaultAgentLoop（完整 Loop 编排）
- [x] S1-15 MessageHistory（内存消息历史管理 + 超长裁剪）
- [x] S1-16 application.yaml 配置
- [x] S1-17 提示词集中维护：新增 `ai.cerbur.cimo.prompt` 包，并将 `CliAgentEntry` 内联 Step 1 system prompt 迁移到 `CimoPrompts.STEP_1_SYSTEM_PROMPT`（2026-05-09 12:54 CST，Git Commit: 未提交）
- [x] S1-18 配置边界重构：`CimoProperties` 移除 provider-specific 字段，新增 `AnthropicProperties` / `OpenAiProperties`，`ClientFactory` 根据 `cimo.provider` 选择 adapter，Spring AI Anthropic 配置引用 `cimo.anthropic` 作为入口（2026-05-09 15:33 CST，Git Commit: 未提交）
- [x] S1-19 Tool 注册边界重构：`config` 包只保留 `application.yaml` → properties bean 的绑定职责，移除/避免 `ToolConfig` 这类在 config 中维护工具注册或装配的逻辑；工具注册放到 `tool.registry` / agent-harness 边界，后续 Harness 独立包出现后迁移到 harness 层再供 agent 引用（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [x] S1-20 Provider Client 懒创建边界重构：`ClientFactory` / provider 配置链路必须先识别 `cimo.provider`，再只创建被选中的 provider client；未来加入 OpenAI 时不能在 Spring bean 创建阶段同时初始化 Anthropic/OpenAI 等所有 provider。若条件化 Bean 不自然，则采用普通工厂模式手动构造唯一 client（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [ ] S1-21 配置边界再次收口：删除无独立职责的 `CimoProperties`；`cimo.provider` 贴近 provider 选择逻辑读取，`cimo.work-dir` 贴近 `AgentContext` 构建读取，`cimo.agent.max-tool-rounds` 从 `application.yaml` 注入 Agent/Context 构建链路；`BashTool` 只读取自身运行参数（如 timeout），不从总配置读取命令白名单（决策时间：2026-05-09 16:24 CST，Git Commit: 30a7095）。
- [ ] S1-22 BashTool 能力配置收口：从 `application.yaml` 移除 `cimo.tool.bash.allowed-commands`；Step 1 的 echo 白名单固定在 `BashTool` 实现和 schema 中，后续扩展命令必须通过代码和测试显式演进（决策时间：2026-05-09 16:24 CST，Git Commit: 30a7095）。
- [ ] S1-23 端到端验证：`通过bash输出hello` 走通（代码已完成，等待配置真实 `ANTHROPIC_API_KEY` 后验证）
- [x] S1-24 编译与上下文验证：`./gradlew test` 通过（2026-05-09 12:41 CST，Git Commit: 未提交）
- [x] S1-25 CLI 启动烟测：`printf 'exit\n' | ./gradlew bootRun` 通过（2026-05-09 12:41 CST，Git Commit: 未提交）
- [x] S1-26 CLI 模式文档收口：`AGENTS.md`、`README.md`、`plan.md` 统一当前方向为 JLine CLI REPL，移除/标注 Spring Shell 命令模式的旧表述；后续代码和依赖再按该方向清理（2026-05-09 16:00 CST，Git Commit: 未提交）

---

## 后续展望

| Step | 内容 | 依赖 |
|------|------|------|
| Step 1 | CLI + Anthropic + BashTool | — |
| Step 2 | 新增 Read/Write/Edit/List/Glob 工具 | Step 1 |
| Step 3 | Session 持久化 + 消息历史文件存储 | Step 1 |
| Step 4 | Harness 管理层 + REST API | Step 1~3 |
| Step 5 | Web 前端独立项目 | Step 4 |
| Step 6 | 安全 + Docker | Step 5 |

---

## 完成记录

| 完成时间 | Plan | Git Commit |
|---------|------|------------|
| 2026-05-09 12:32 CST | Step 1 coding 完成；真实 Anthropic 端到端验证待配置 `ANTHROPIC_API_KEY` 后执行 | 未提交 |
| 2026-05-09 12:54 CST | Step 1 提示词集中维护：迁移 CLI 内联 system prompt 到 `ai.cerbur.cimo.prompt.CimoPrompts` | 未提交 |
| 2026-05-09 15:33 CST | 配置边界重构：拆分 Cimo 全局配置与 LLM provider 专属配置，`ClientFactory` 根据 `cimo.provider` 选择 adapter，Spring AI Anthropic 配置改为引用 `cimo.anthropic` | 未提交 |
| 2026-05-09 16:00 CST | CLI 模式文档收口：`AGENTS.md`、`README.md`、`plan.md` 统一当前方向为 JLine CLI REPL，后续代码和依赖按该方向清理 | 未提交 |
| 2026-05-09 16:09 CST | S1-13 / S1-19 / S1-20：删除无职责 `ToolSpec`；移除 config 层工具装配；改为手动按选中 provider 创建 Anthropic client，避免 Spring AI provider 自动配置提前初始化 | 未提交 |
| 2026-05-09 16:24 CST | S1-21 / S1-22 决策：`CimoProperties` 无独立职责，计划删除；`maxToolRounds` 从 `application.yaml` 注入 Agent/Context 构建链路；Bash 命令白名单固定在 `BashTool` 实现和 schema，不作为 YAML 配置 | 30a7095 |
| 2026-05-09 16:30 CST | Plan 文件收口：根目录 `plan.md` 迁移为 `.plan/plan_step1.md`，新增 `.plan/plan_main.md` 作为总入口 | 5e6875f |

## 决策记录

| 时间 | 决策 | Git Commit |
|------|------|------------|
| 2026-05-09 03:35 CST | Step 1 可以进入执行前准备；开工前补充 Spring AI Anthropic 集成方式、AgentState、StreamEvent、tool_use_id 四个细节。 | b39d7ef |
| 2026-05-09 12:15 CST | Step 1 执行决策：ChatMessage 使用 block-based 模型；Spring AI 负责配置和 Anthropic 接入，Cimo 自己掌控 Agent Loop 与 tool_result 协议；当时 CLI 考虑使用 Spring Shell，后续已被 12:23 和 16:00 决策修正为 JLine REPL；BashTool 先采用白名单命令；AgentState 命名统一为大写枚举。 | f1704a0 |
| 2026-05-09 12:23 CST | Step 1 进一步收窄：BashTool 白名单仅支持 echo；system prompt 通过 ClientRequest 独立传入；CLI 采用类似 Codex / Claude Code 的 JLine 交互式 REPL，而不是 Spring Shell 命令模式。 | 未提交 |
| 2026-05-09 12:41 CST | 修正 Anthropic 接入：`SpringAiAnthropicClient` 必须基于 Spring AI `AnthropicChatModel`，由 `spring-ai-anthropic` 维护底层 Anthropic client；Cimo 仅负责消息模型适配和外部工具执行。 | 未提交 |
| 2026-05-09 12:46 CST | 提示词统一抽象到 `ai.cerbur.cimo.prompt` 包维护；入口层、Agent Loop、Client adapter 不再内联大段 prompt，只引用统一包暴露的常量、模板或工厂方法。 | 4c1c015 |
| 2026-05-09 15:28 CST | 修正配置边界：`CimoProperties` 只承载 Cimo 全局运行配置；Anthropic、OpenAI 等 provider 的 apiKey/model/baseUrl 拆到独立 provider properties/config。启动时先根据 `cimo.provider` 选择 LLM adapter，再绑定/使用对应 provider 配置，避免总配置类聚合多个 LLM provider。 | 4c1c015 |
| 2026-05-09 15:40 CST | 修正 Tool 注册边界：`config` 包只做 `application.yaml` 到 properties bean 的识别和绑定，不维护工具注册、工具列表或工具装配；工具注册属于 harness/agent 能力边界，先保留在 `tool.registry`，后续 Harness 独立包出现后迁移到 harness 层再供 agent 引用。 | 未提交 |
| 2026-05-09 15:49 CST | `ToolSpec` 无代码引用，且只是 Anthropic schema 的薄转换层；当前转换职责应留在 provider adapter 内部。按第一性原理和奥卡姆剃刀，不保留没有独立职责和当前收益的抽象，进入编码阶段后删除该文件。 | 4c1c015 |
| 2026-05-09 15:55 CST | 修正 Provider Client 创建边界：不能在 Spring bean 创建阶段初始化所有 LLM provider 再由 `ClientFactory` 选择其一；必须先识别 `cimo.provider`，再只创建被选中的 provider client。若条件化 Bean 让生命周期复杂化，则退回普通工厂模式以保持最小设计。 | 4c1c015 |
| 2026-05-09 16:00 CST | 确认入口方向使用 CLI/JLine REPL 模式：`./gradlew bootRun` 后直接进入自然语言交互提示符，不采用 Spring Shell 命令模式；README、AGENTS、plan 先统一该方向，后续代码和依赖按此收口。 | 未提交 |
| 2026-05-09 16:24 CST | 修正运行配置边界：`CimoProperties` 当前只是把 provider/work-dir/agent/tool 临时聚合，没有独立职责和可验证收益，应删除；各配置改由真正使用它们的组件读取。`maxToolRounds` 从 `application.yaml` 注入 Agent/Context 构建链路；Bash 的命令白名单属于 `BashTool` 能力定义，不进入 YAML 或总配置类。 | 30a7095 |
| 2026-05-09 16:30 CST | 计划文件统一收口到 `.plan/`：`plan_main.md` 作为总入口，`plan_step1.md` 承载当前 Step 1 详细计划；根目录不再维护 `plan.md`。 | 5e6875f |
