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
| 内存 MessageHistory（纯追加，无上限无裁剪） | Context 上限/压缩/裁剪 |

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
    ├── CimoProperties.java           — Cimo 一级运行配置：provider / debug / work-dir / agent
    ├── AnthropicProperties.java      — Anthropic provider 专属配置
    └── OpenAiProperties.java         — OpenAI provider 专属配置（后续 Step 启用）
```

> 2026-05-10 修正：`SpringEnvironmentReader` 的静态读 `Environment` 设计不再作为 Step 1 方向。Cimo 一级配置已经形成稳定边界：`provider` 决定 provider 选择，`debug` 是全局诊断开关，`work-dir` 决定运行上下文，`agent` 承载 Agent Loop 运行参数；应重新引入 `CimoProperties` 聚合这些 Cimo 级配置，并在使用处显式注入。2026-05-09 关于删除 `CimoProperties` 的旧结论被本决策覆盖；provider-specific 字段仍不放入 `CimoProperties`，继续由 `AnthropicProperties` / `OpenAiProperties` 等专属配置承载。

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
  debug: false
  work-dir: ${user.dir}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    model: claude-sonnet-4-20250514
    base-url: ${ANTHROPIC_BASE_URL:}
    max-tokens: 4096
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
- **Anthropic 必填配置与启动失败边界**：`AnthropicProperties.baseUrl` 必须从配置绑定读取，`AnthropicProperties` 构造器不再写死 `https://api.anthropic.com` 或任何默认值；`model` 仍可由配置文件给出默认模型，但不由 properties 构造器兜底。`provider=anthropic` 时，`ClientFactory.createAnthropicChatModel()` 链路必须在创建 `AnthropicChatModel` 前校验 Anthropic 必要值：`apiKey`、`model`、`baseUrl` 均不能为空白，且 `baseUrl` 必须是合法 HTTP/HTTPS URL。校验失败时抛出清晰的启动期异常，让 Spring Boot 启动失败，不进入 CLI REPL，也不延迟到第一次用户输入或第一次 API 调用才失败。决策时间：2026-05-09 17:01 CST，Git Commit: 未提交。
- **AnthropicProperties 按需加载验证**：编码阶段需要尝试让 `AnthropicProperties` 只在 `provider=anthropic` 时参与加载/绑定/校验，避免 `provider=openai` 或后续其他 provider 时因为 Anthropic 缺少 `apiKey/baseUrl` 而阻塞启动。优先方案是条件化 provider 配置或条件化 provider client bean；如果 Spring Boot `@ConfigurationProperties` 生命周期不适合完全按需加载，则接受 properties bean 被绑定，但必须保证强校验只发生在 `createAnthropicChatModel()` 或 Anthropic 专属链路内，未选中 provider 不触发 Anthropic 必填检查。决策时间：2026-05-09 17:01 CST，Git Commit: 未提交。
- **S1-27~S1-30 合并执行方案**：这四项视为同一个 Anthropic 配置契约收口任务，一次性处理，避免在配置默认值、启动校验、按需加载和测试之间反复改动。执行顺序：先收口 `AnthropicProperties.baseUrl`，确保只从 `application.yaml` / `ANTHROPIC_BASE_URL` 绑定；再在 `ClientFactory.createAnthropicChatModel()` 或等价的 Anthropic provider 创建链路中集中校验 `apiKey/model/baseUrl`；随后验证非 Anthropic provider 不触发 Anthropic 必填校验或 SDK client 创建；最后补齐失败路径测试。该合并任务不把 provider-specific 字段塞回 `CimoProperties`，也不新增抽象层来提前包装校验逻辑；只有当测试证明校验规则被多个 provider 共享时，才考虑提取通用 validator。决策时间：2026-05-10 02:57 CST，Git Commit: a46cd2c。
- **全局 Debug 输出边界**：新增 `cimo.debug` 配置，默认 `false`。当且仅当 `cimo.debug=true` 且 `provider=anthropic` 时，`ClientFactory.createAnthropicChatModel()` 在 `validateAnthropicProperties()` 之后、构建 `AnthropicChatModel` 之前，向 CLI 打印当前 Anthropic 配置信息，帮助确认启动时实际绑定到的 `model`、`baseUrl`、`maxTokens` 等值。第一性原理：这个开关表达的是“本次 CLI 运行允许输出诊断信息”，后续其他模块若需要 debug 输出也复用同一标识；该开关属于 Cimo 一级运行配置，应通过 `CimoProperties` 显式注入后读取，不再通过 `SpringEnvironmentReader` 静态读取 Spring `Environment`。安全边界：`apiKey` 只能脱敏打印，例如仅展示是否已配置、长度或首尾少量字符，不能完整输出凭据。决策时间：2026-05-10 03:27 CST，Git Commit: 未提交；读取方式修正时间：2026-05-10 CST，Git Commit: 未提交。
- **CLI Tool 输出展示边界**：默认 CLI 输出面向用户，不直接打印 provider 原始 `tool_use` JSON 或完整 `tool_result` 原始数据；这类原始协议内容只在 `cimo.debug=true` 时作为诊断信息输出。`AgentEvent.ToolCall` 默认展示为人类可读摘要，例如 `Tool: bash echo hello`；`AgentEvent.ToolResult` 默认展示需要带工具名上下文，例如 `Result: bash: hello`，避免只有 `Result: hello` 时无法判断结果来源。验收时以真实交互截图中暴露的问题为准：`Tool: bash {"command":"echo","args":["hello"]}` 和裸 `Result: hello` 都需要收口。决策时间：2026-05-10 03:39 CST，Git Commit: 91b389d。
- **Tool 注册边界**：`config` 包只负责把 `application.yaml` 识别并绑定成 properties bean，不维护工具注册、工具列表或工具装配逻辑。工具注册属于 harness/agent 层面的能力边界：Step 1 可先由 `tool.registry.ToolRegistry` 收集可用 `Tool`，后续若引入独立 Harness 管理层，则把注册/选择/暴露工具的逻辑迁移到 harness 包，再由 agent 层按上下文引用注册结果。
- **CimoProperties 重新引入边界**：`SpringEnvironmentReader` 用静态状态按需读取 Spring `Environment`，让配置来源和业务组件依赖关系变得隐式，不利于测试和后续代理理解；Step 1 应重新定义 `CimoProperties` 作为 Cimo 一级运行配置对象，承载 `provider`、`debug`、`work-dir`、`agent.max-tool-rounds` 等跨组件共享且属于 Cimo 自身运行语义的字段。边界要求：`CimoProperties` 不聚合 `anthropic`、`openai` 等 provider-specific 配置；`tool.bash.timeout-seconds` 仍优先由 `BashToolProperties` 或 BashTool 自身窄配置承载；`allowed-commands` 仍不进入配置。此决策覆盖 2026-05-09 的 `CimoProperties` 删除方向。决策时间：2026-05-10 CST，Git Commit: 未提交。
- **Spring 注入风格边界**：普通 Spring 依赖优先使用成员变量上的 `@Autowired` 显式注入，避免构造器只做字段搬运导致业务依赖藏在构造器参数里。例外：构造器中确实存在初始化逻辑、派生对象创建、不可变状态计算或启动期校验时，可以保留构造器注入，例如 `DefaultAgentLoop` 当前构造器通过 `ClientFactory.createClient()` 创建并保存 `Client`。编码阶段需要按该规范梳理现有组件。
- **BashTool 能力边界**：Step 1 支持哪些命令不是运行时配置能力，而是 `BashTool` 自身实现和 schema 暴露的一部分。`allowed-commands` 不应出现在 `CimoProperties` 或 `application.yaml` 中，否则配置可以绕过当前 Step 的能力边界。Step 1 固定只支持 `echo`；后续扩展命令时，必须通过修改 `BashTool` 的解析、校验、schema 和测试来显式扩大能力。
- **CLI 形态**：做成类似 Codex / Claude Code 的启动即交互体验：`./gradlew bootRun` 后进入 `>` 提示符，用户直接输入自然语言；底层用 JLine 读取输入并接入 Spring 生命周期，不采用 Spring Shell 的 `ask ...` 命令模式，也不使用裸 `Scanner`。
- **CLI 模式确认**：当前方向明确使用 CLI/JLine REPL 模式作为 Step 1 入口，不再沿用 Spring Shell 命令模式；文档、依赖和入口实现都应围绕“启动即进入自然语言 REPL”收口。决策时间：2026-05-09 16:00 CST，Git Commit: 未提交。
- **Bash 安全边界**：Step 1 的白名单只开放 `echo`；更复杂的命令、参数解析、路径隔离、命令注入防护和沙箱策略全部放到后续安全 Step 中迭代。
- **BashTool 参数结构**：Step 1 不接收整条 shell 字符串，只接收结构化参数，例如 `{ "command": "echo", "args": ["hello"] }`，执行时由 `BashTool` 组装为受控的 `echo` 调用。
- **状态事件收口**：Step 1 没有 Session / Harness / API 等真实状态消费者，CLI 对状态变化也没有可观察输出收益；当前事件模型只保留 `Thinking`、`ToolCall`、`ToolResult`、`Response`、`Error` 等入口层确实需要展示的事件。`AgentState` / `StatusChange` 等状态模型等 Step 3 Session 或 Step 4 Harness/API 出现真实消费者时再重新设计并引入。

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
- [x] S1-05 AgentState 枚举定义（INITIALIZING / RUNNING / WAITING_FOR_TOOL / COMPLETED / ERROR / SHUTDOWN；后续由 S1-39 删除无消费者状态模型）
- [x] S1-06 ChatMessage 改为 block-based 模型（Text / ToolUse / ToolResult）
- [x] S1-07 System prompt 通过 ClientRequest 显式传入
- [x] S1-08 SpringAiAnthropicClient 流式调用 + tool_use 响应解析
- [x] S1-09 StreamEvent 支持 text_delta / tool_use_start / tool_input_delta / message_stop 等最小事件
- [x] S1-10 tool_use_id 在消息历史中保存，并用于后续 tool_result 回传
- [x] S1-11 BashTool（安全限制：超时 + 白名单仅 echo）
- [x] S1-12 ToolRegistry（Spring 自动收集 Tool Bean）
- [x] S1-13 删除无引用的 `ToolSpec`：按第一性原理和奥卡姆剃刀，当前 schema 转换职责属于 provider adapter；`ToolSpec` 无独立职责且没有代码引用，进入编码阶段后删除文件（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [x] S1-14 DefaultAgentLoop（完整 Loop 编排）
- [x] S1-15 MessageHistory（纯追加，无上限无裁剪；后续压缩逻辑时整块重构）
- [x] S1-16 application.yaml 配置
- [x] S1-17 提示词集中维护：新增 `ai.cerbur.cimo.prompt` 包，并将 `CliAgentEntry` 内联 Step 1 system prompt 迁移到 `CimoPrompts.STEP_1_SYSTEM_PROMPT`（2026-05-09 12:54 CST，Git Commit: 未提交）
- [x] S1-18 配置边界重构：`CimoProperties` 移除 provider-specific 字段，新增 `AnthropicProperties` / `OpenAiProperties`，`ClientFactory` 根据 `cimo.provider` 选择 adapter，Spring AI Anthropic 配置引用 `cimo.anthropic` 作为入口（2026-05-09 15:33 CST，Git Commit: 未提交）
- [x] S1-19 Tool 注册边界重构：`config` 包只保留 `application.yaml` → properties bean 的绑定职责，移除/避免 `ToolConfig` 这类在 config 中维护工具注册或装配的逻辑；工具注册放到 `tool.registry` / agent-harness 边界，后续 Harness 独立包出现后迁移到 harness 层再供 agent 引用（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [x] S1-20 Provider Client 懒创建边界重构：`ClientFactory` / provider 配置链路必须先识别 `cimo.provider`，再只创建被选中的 provider client；未来加入 OpenAI 时不能在 Spring bean 创建阶段同时初始化 Anthropic/OpenAI 等所有 provider。若条件化 Bean 不自然，则采用普通工厂模式手动构造唯一 client（完成时间：2026-05-09 16:09 CST，Git Commit: 未提交）
- [x] S1-21 配置边界再次收口：删除无独立职责的 `CimoProperties`；`cimo.provider` 贴近 provider 选择逻辑读取，`cimo.work-dir` 贴近 `AgentContext` 构建读取，`cimo.agent.max-tool-rounds` 从 `application.yaml` 注入 Agent/Context 构建链路；`BashTool` 只读取自身运行参数（如 timeout），不从总配置读取命令白名单（完成时间：2026-05-09 16:38 CST，Git Commit: 7e8c984；2026-05-10 后续修正：Cimo 一级配置边界已成形，重新引入 `CimoProperties`，见 S1-36）。
- [x] S1-22 BashTool 能力配置收口：从 `application.yaml` 移除 `cimo.tool.bash.allowed-commands`；Step 1 的 echo 白名单固定在 `BashTool` 实现和 schema 中，后续扩展命令必须通过代码和测试显式演进（完成时间：2026-05-09 16:38 CST，Git Commit: 7e8c984）。
- [x] S1-23 端到端验证：`通过bash输出hello` 走通（完成时间：2026-05-11 01:52 CST，Git Commit: 5142e45；验证方式：关闭 CLI runner 后通过真实 Spring `AgentLoop + Anthropic + BashTool` 链路断言触发 `bash echo` 且结果包含 `hello`）
- [x] S1-24 编译与上下文验证：`./gradlew test` 通过（2026-05-09 12:41 CST，Git Commit: 未提交）
- [x] S1-25 CLI 启动烟测：`printf 'exit\n' | ./gradlew bootRun` 通过（2026-05-09 12:41 CST，Git Commit: 未提交）
- [x] S1-26 CLI 模式文档收口：`AGENTS.md`、`README.md`、`plan.md` 统一当前方向为 JLine CLI REPL，移除/标注 Spring Shell 命令模式的旧表述；后续代码和依赖再按该方向清理（2026-05-09 16:00 CST，Git Commit: 未提交）
- [x] S1-27 Anthropic baseUrl 配置契约收口：`AnthropicProperties.baseUrl` 只从配置读取，不在 properties 构造器中设置默认值；`application.yaml` 暴露 `cimo.anthropic.base-url` / `ANTHROPIC_BASE_URL` 入口，默认是否为空由启动校验负责解释（完成时间：2026-05-10 03:00 CST，Git Commit: 0c5f34d）。
- [x] S1-28 Anthropic 启动期必要值校验：当 `cimo.provider=anthropic` 时，`ClientFactory.createAnthropicChatModel()` 在构建 Spring AI `AnthropicChatModel` 前校验 `apiKey`、`model`、`baseUrl`；失败时抛出明确异常并中止应用启动（完成时间：2026-05-10 03:00 CST，Git Commit: 0c5f34d）。
- [x] S1-29 AnthropicProperties 按需加载验证：尝试让 `AnthropicProperties` 仅在 Anthropic provider 链路需要时加载；若生命周期成本高，则至少保证未选中 Anthropic provider 时不触发 Anthropic 必填校验、不创建 Anthropic SDK client（完成时间：2026-05-10 03:00 CST，Git Commit: 0c5f34d）。
- [x] S1-30 配置失败测试补充：新增/调整测试覆盖 `provider=anthropic` 缺少 `apiKey/model/baseUrl` 时启动或 client 创建失败、`baseUrl` 非 HTTP/HTTPS URL 时失败，以及非 Anthropic provider 不因 Anthropic 配置缺失而失败（完成时间：2026-05-10 03:00 CST，Git Commit: 0c5f34d）。
- [x] S1-31 Context 上限不在 Step 1 做：`MessageHistory` 去掉 `maxMessages` 和 `trim()`，简化为纯追加；后续加压缩逻辑时整块重构（完成时间：2026-05-10 12:30 CST，Git Commit: 未提交）
- [x] S1-32 收口无消费者的状态事件：`DefaultAgentLoop` 当前发出 `AgentEvent.StatusChange(AgentState.WAITING_FOR_TOOL / COMPLETED / ERROR / SHUTDOWN)`，但 CLI 入口直接忽略 `StatusChange`，Step 1 没有 UI/API/Session/Harness 等真实消费者。按第一性原理，状态模型暂时没有可观察收益；后续编码阶段应删除或暂停扩展 `AgentState` / `StatusChange`，保留 `ToolCall`、`ToolResult`、`Response`、`Error` 等有真实输出价值的事件。等 Step 3 Session 或 Step 4 Harness/API 出现状态消费者时再重新引入。（完成时间：2026-05-11 02:37 CST，Git Commit: 0886208；由 S1-39 落地）
- [x] S1-33 Anthropic 输出 token 上限配置化：`SpringAiAnthropicClient` 中 `AnthropicChatOptions.maxTokens` 改为从 Anthropic provider 配置读取，`application.yaml` 提供 `cimo.anthropic.max-tokens: 4096`。该参数只限制单次模型输出长度，不代表上下文窗口；默认 4096 更适合作为 CLI Agent 的基础输出预算（完成时间：2026-05-10 02:52 CST，Git Commit: 未提交）。
- [x] S1-34 Anthropic 配置 debug 输出：`application.yaml` 增加全局 `cimo.debug: false`；`ClientFactory` 在具体 debug 输出逻辑需要时读取该全局开关，并在 `validateAnthropicProperties()` 之后根据该开关打印 Anthropic 配置信息到 CLI；`AnthropicProperties` 不承载 debug 字段。验收标准：`debug=false` 时不输出配置；`debug=true` 时输出 `model/baseUrl/maxTokens/debug` 等可排查信息；`apiKey` 必须脱敏，不能完整打印。（完成时间：2026-05-10 03:27 CST，Git Commit: 未提交；2026-05-10 后续修正：读取方式由 S1-36 改为显式注入 `CimoProperties`，不再使用 `SpringEnvironmentReader`）
- [x] S1-35 CLI Tool 事件输出收口：默认模式不打印 provider 原始 `tool_use` JSON 或完整 `tool_result` 原始数据；原始协议内容仅在 `cimo.debug=true` 时输出。默认 `ToolCall` 展示为人类可读摘要，例如 `Tool: bash echo hello`；默认 `ToolResult` 展示带工具来源，例如 `Result: bash: hello`。补充测试覆盖：`debug=false` 不出现 `{"command":"echo","args":["hello"]}` 这类原始 JSON；`debug=true` 输出原始 tool use 诊断信息；结果展示包含工具名上下文。（完成时间：2026-05-11 01:59 CST，Git Commit: 4641b50）
- [x] S1-36 实现 `CimoProperties` 替代 `SpringEnvironmentReader` 的配置读取逻辑：重新定义 `ai.cerbur.cimo.config.CimoProperties` 作为 `cimo` 一级运行配置绑定对象，承载 `provider`、`debug`、`work-dir`、`agent.max-tool-rounds` 等 Cimo 自身配置；删除 `SpringEnvironmentReader` 的静态 `Environment` 读取方式；`ClientFactory`、入口/上下文构建链路改为读取显式注入的 `CimoProperties`。边界：`AnthropicProperties` / `OpenAiProperties` 继续承载 provider-specific 配置；Bash timeout 继续保留在工具窄配置；Bash allowed commands 不配置化。验收：配置绑定测试覆盖默认值和 `cimo.debug` / `cimo.provider` / `cimo.work-dir` / `cimo.agent.max-tool-rounds`；原有 Anthropic 配置校验和非选中 provider 不触发校验的测试通过。（完成时间：2026-05-11 01:59 CST，Git Commit: 4641b50）
- [x] S1-37 按 `@Autowired` 成员变量注入规范重新梳理代码：除构造器中存在真实初始化逻辑、派生对象创建或校验逻辑的类外，Spring Bean 依赖统一改为成员变量上的 `@Autowired` 显式注入，不再用构造器只做字段赋值。明确例外：`DefaultAgentLoop` 当前 `DefaultAgentLoop(ClientFactory clientFactory)` 中调用 `clientFactory.createClient()` 并保存 `Client`，属于构造器内有逻辑处理，可以保留构造器注入。验收标准：检查所有 `@Component` / `@Configuration` / `@Service` 等 Spring 管理类；调整测试构造方式；确保 `./gradlew test` 通过。（完成时间：2026-05-11 02:13 CST，Git Commit: 4663fb7）
- [x] S1-38 按 AGENTS.md 注释质量要求补充代码注释：为主代码中的 public 类、接口、record、enum 补充清晰中文类级注释，说明职责、输入输出或关键约束；为重要业务方法、公共方法、流程编排方法和带关键边界的方法补充中文注释；在 Agent Loop、provider adapter、工具执行、配置校验、CLI 输出等核心状态流转或边界分支处补充必要行注释。第一性原理：注释只解决“代码命名和结构无法直接表达的业务意图、设计约束、状态流转、边界假设或历史决策”，不为 getter/setter、简单私有辅助方法或一眼可读的代码补机械注释。验收标准：不改变运行行为；不新增抽象；不引入英文长篇注释；`./gradlew test` 通过。计划记录时间：2026-05-11 02:19 CST，Git Commit: 5af892d；完成时间：2026-05-11 02:24 CST，Git Commit: eb90938。
- [x] S1-39 落地收口无消费者的 `AgentState` / `StatusChange`：基于 S1-32 的判断，删除当前 Step 1 没有真实消费者的状态事件，或明确暂停扩展并从 `DefaultAgentLoop` 当前事件流中移除；保留 `ToolCall`、`ToolResult`、`Response`、`Error` 等有真实输出价值的事件。验收标准：CLI 交互可观察行为不退化；相关测试同步调整；`./gradlew test` 通过；完成记录说明状态模型等 Step 3 Session 或 Step 4 Harness/API 出现真实消费者时再重新引入。（完成时间：2026-05-11 02:37 CST，Git Commit: 0886208；验证：`./gradlew test` 通过）
- [x] S1-41 补充真实 CLI 交互验证记录：在真实终端运行 `./gradlew bootRun`，输入 `通过bash输出hello`，确认 JLine REPL、Anthropic 调用、`bash echo` tool call、tool result 展示和最终回复都能在 CLI 中完整走通。验收标准：记录验证时间、验证方式和对应 Git Commit；如果当前工具环境仍无法通过 stdin 驱动 JLine，需明确区分“真实终端手动验证”和“测试替身/AgentLoop 端到端验证”的边界。完成时间：2026-05-11 02:40 CST，Git Commit: 0886208；验证方式：先发现 `bootRun` 未转交标准输入会导致 JLine 立即 EOF，随后在 `build.gradle` 为 `bootRun` 设置 `standardInput = System.in`，再用 expect 伪终端驱动真实 `./gradlew bootRun`，输入 `通过bash输出hello` 后观察到 `Thinking: Calling Anthropic...`、`Tool: bash echo hello`、`Result: bash: hello`、最终回复包含 `hello`，并输入 `exit` 后正常 `Bye!` / `BUILD SUCCESSFUL`。

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
| 2026-05-09 16:38 CST | S1-21 / S1-22：删除无独立职责的 `CimoProperties`；provider/work-dir/max-tool-rounds 改为贴近使用点注入；BashTool 只保留 timeout 配置，echo 白名单固定在实现和 schema，并补充边界测试 | 7e8c984 |
| 2026-05-10 12:30 CST | S1-31 Context 上限不在 Step 1 做：按奥卡姆剃刀原则，`MessageHistory` 改为纯追加模式，去掉 `maxMessages` 和 `trim()`；后续加压缩逻辑时整块重构 | 未提交 |
| 2026-05-10 02:52 CST | S1-33 Anthropic 输出 token 上限配置化：`SpringAiAnthropicClient` 从 `cimo.anthropic.max-tokens` 读取单次输出预算，默认 4096，不再硬编码 1024 | 未提交 |
| 2026-05-10 03:00 CST | S1-27 / S1-28 / S1-29 / S1-30：Anthropic `baseUrl` 不再由 properties 构造器兜底；`ClientFactory` 在 Anthropic client 创建前集中校验 `apiKey/model/baseUrl`；非 Anthropic provider 不触发 Anthropic 校验；补充配置失败测试 | 0c5f34d |
| 2026-05-10 03:27 CST | S1-34 Anthropic 配置 debug 输出：新增全局 `cimo.debug` 开关，校验通过后按需打印 `model/baseUrl/maxTokens/debug` 和脱敏后的 `apiKey`；当时通过 `SpringEnvironmentReader` 从 Spring `Environment` 读取开关；2026-05-10 后续修正为通过 `CimoProperties` 显式注入读取，见 S1-36 | 未提交 |
| 2026-05-11 01:52 CST | S1-23 真实 Anthropic 端到端验证：`通过bash输出hello` 触发 `bash echo` tool call，BashTool 返回结果包含 `hello`；CLI stdin 在当前工具环境中会被 JLine 读成 EOF，因此验证改为关闭 CLI runner 后调用真实 Spring `AgentLoop + Anthropic + BashTool` 链路 | 5142e45 |
| 2026-05-11 01:59 CST | S1-35 / S1-36：CLI Tool 默认输出改为人类可读摘要，debug 模式才附带原始 tool 参数；重新引入 `CimoProperties` 作为 Cimo 一级配置对象，并删除 `SpringEnvironmentReader` 静态读取链路；补充 CLI 输出、配置绑定和 ClientFactory debug 测试 | 4641b50 |
| 2026-05-11 02:13 CST | S1-37 Spring 注入风格收口：`ClientFactory`、`CliAgentEntry` 改为成员变量 `@Autowired` 注入；保留存在派生对象创建或初始化逻辑的构造器；同步调整测试构造方式；`./gradlew test` 通过 | 4663fb7 |
| 2026-05-11 02:19 CST | S1-38 计划补充：按 AGENTS.md 注释质量要求，为主代码补充职责、边界、状态流转和关键意图注释；不做机械注释、不改变行为 | 5af892d |
| 2026-05-11 02:24 CST | S1-38 注释补充完成：为主代码 public 类型补充中文职责注释，并在 Agent Loop、Anthropic adapter、BashTool、CLI 输出和 provider 创建边界补充关键意图说明；`./gradlew test` 通过 | eb90938 |
| 2026-05-11 02:40 CST | S1-41 真实 CLI 交互验证完成：修正 `bootRun` 标准输入转交后，通过 expect 伪终端运行 `./gradlew bootRun`，输入 `通过bash输出hello`，确认 JLine REPL、Anthropic 调用、`bash echo` tool call、tool result 展示、最终回复和 `exit` 退出链路完整走通 | 0886208 |
| 2026-05-11 02:37 CST | S1-39 状态事件收口：删除 Step 1 无真实消费者的 `AgentState` / `StatusChange`，`DefaultAgentLoop` 不再发出状态事件，CLI 不再保留空消费分支；状态模型等 Step 3 Session 或 Step 4 Harness/API 出现真实消费者时再重新引入；`./gradlew test` 通过 | 0886208 |

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
| 2026-05-09 17:01 CST | 细化 Anthropic 配置契约：`baseUrl` 从配置读取且不在 properties 构造器兜底；`provider=anthropic` 时在 `ClientFactory.createAnthropicChatModel()` 链路校验 `apiKey/model/baseUrl`，失败即中止启动；同时尝试让 `AnthropicProperties` 只在 Anthropic provider 需要时加载，至少保证未选中 provider 不触发 Anthropic 必填校验。 | 未提交 |
| 2026-05-10 12:30 CST | Context 上限不在 Step 1 做：`MessageHistory` 简化为纯追加模式，去掉 `maxMessages` 参数和 `trim()` 逻辑；后续叠加压缩/窗口/摘要逻辑时整个统一重做。依据：Step 1 的核心目标是跑通 Agent Loop 链路，context 管理是独立且影响整个架构的能力，现在做一个简化版本反而会在重构时成为负债。奥卡姆剃刀——不做。 | 未提交 |
| 2026-05-10 02:18 CST | `AgentState` / `StatusChange` 在 Step 1 当前没有真实消费者：`CliAgentEntry` 直接忽略状态事件，`WAITING_FOR_TOOL` 等状态不影响输出、控制流、工具执行、错误处理或测试断言。后续应先收口这类无可观察收益的状态维护，等 Session/Harness/API 需要状态时再重新设计。 | 924dd94 |
| 2026-05-10 02:57 CST | S1-27~S1-30 作为同一个 Anthropic 配置契约收口任务合并处理：配置来源、启动期校验、非选中 provider 不触发校验、失败路径测试必须一起落地；不引入新的全局配置聚合类或提前抽象的通用 validator。 | a46cd2c |
| 2026-05-10 03:27 CST | 修正 S1-34 debug 读取方式：按用户确认，debug 是全局运行诊断标识 `cimo.debug`，后续其他 debug 逻辑复用该标识；当时只在 Anthropic client 创建链路的具体输出逻辑中通过 `SpringEnvironmentReader` 从 Spring `Environment` 读取它来打印脱敏配置，不把 debug 放入 `AnthropicProperties`；2026-05-10 后续决策已改为通过 `CimoProperties` 显式注入读取。 | 未提交 |
| 2026-05-10 03:39 CST | 修正 CLI Tool 事件展示边界：默认交互只展示人类可读摘要，不暴露 provider 原始 `tool_use` JSON 或完整 `tool_result` 原始数据；原始协议内容归入 `cimo.debug=true` 诊断输出。默认结果展示需要带工具来源，例如 `Result: bash: hello`，避免裸 `Result: hello` 缺少上下文。 | 91b389d |
| 2026-05-10 CST | 修正 Cimo 一级配置设计：`SpringEnvironmentReader` 的静态 `Environment` 读取方式不再保留；重新引入 `CimoProperties` 承载 `provider`、`debug`、`work-dir`、`agent` 等 Cimo 自身运行配置，并通过显式注入供使用处读取。该决策覆盖 2026-05-09 删除 `CimoProperties` 的旧方向，但不改变 provider-specific 配置继续拆到 `AnthropicProperties` / `OpenAiProperties` 的边界。 | 未提交 |
| 2026-05-10 CST | 修正 Spring 注入规范：除构造器中有真实初始化逻辑、派生对象创建或校验逻辑的类外，Spring Bean 依赖统一使用成员变量 `@Autowired` 显式注入；`DefaultAgentLoop` 通过 `ClientFactory.createClient()` 创建 `Client` 属于例外，可保留构造器注入。 | 未提交 |
| 2026-05-11 02:19 CST | 注释补充边界：只补足主代码中 public 类型职责、核心流程、关键约束和不易误改的边界说明；简单样板代码不补机械注释，避免注释噪声盖过代码本身。 | 5af892d |
| 2026-05-11 CST | Step 1 收尾范围确认：加入 S1-39 状态事件收口和 S1-41 真实 CLI 交互验证；Spring AI Anthropic 流式 delta 语义放入 Step 2；`ClientFactory` 与 `ToolRegistry` 的长期边界先不在 Step 1 继续展开。 | 未提交 |
