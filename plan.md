# Cimo Plan

> 从零构建一个 Agent Harness 工具。每个 Step 做最小的事，逐步演进。

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
├── tool                           ← 工具定义 + 实现
│   ├── Tool.java                    — 接口
│   ├── ToolResult.java              — 结果封装
│   ├── ToolSpec.java                — Tool → Anthropic Tool Schema 转换
│   ├── registry/
│   │   └── ToolRegistry.java        — 工具注册表（Spring IoC 收集）
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
    ├── CimoProperties.java           — 配置属性（provider、api-key、model、work-dir）
    └── ToolConfig.java               — 工具安全配置（超时、白名单命令）
```

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

// ClientFactory 使用 Spring AI 管配置、自动配置和 Anthropic 接入
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
  tool:
    bash:
      timeout-seconds: 30
      allowed-commands: echo
```

### System Prompt（Step 1）

Step 1 使用一个很窄的 system prompt，目标是稳定触发 `bash` 工具并避免模型越过当前能力边界：

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
- **System Prompt**：不把 system prompt 塞进 `ChatMessage`；通过 `ClientRequest.systemPrompt` 显式传入，由 Anthropic adapter 映射到 provider 原生 system 字段。
- **Anthropic 接入**：用 Spring AI 管配置、自动配置和 Anthropic 接入；Agent Loop、tool 调用、`tool_result` 回传协议由 Cimo 自己掌控。
- **CLI 形态**：做成类似 Codex / Claude Code 的启动即交互体验：`./gradlew bootRun` 后进入 `>` 提示符，用户直接输入自然语言；底层用 JLine 读取输入并接入 Spring 生命周期，不采用 Spring Shell 的 `ask ...` 命令模式，也不使用裸 `Scanner`。
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

- [ ] 所有包和接口定义完毕
- [ ] 明确 Anthropic 集成方式：Spring AI 管配置和接入，Cimo 自己掌控 Agent Loop 与 tool_result 协议
- [ ] CliAgentEntry（JLine 交互式 REPL + 事件输出）
- [ ] AgentEvent 模型（sealed class）
- [ ] AgentState 枚举定义（INITIALIZING / RUNNING / WAITING_FOR_TOOL / COMPLETED / ERROR / SHUTDOWN）
- [ ] ChatMessage 改为 block-based 模型（Text / ToolUse / ToolResult）
- [ ] System prompt 通过 ClientRequest 显式传入
- [ ] SpringAiAnthropicClient 流式调用 + tool_use 响应解析
- [ ] StreamEvent 支持 text_delta / tool_use_start / tool_input_delta / message_stop 等最小事件
- [ ] tool_use_id 在消息历史中保存，并用于后续 tool_result 回传
- [ ] BashTool（安全限制：超时 + 白名单仅 echo）
- [ ] ToolRegistry（Spring 自动收集 Tool Bean）
- [ ] ToolSpec（Tool → Anthropic Tool Schema 转换）
- [ ] DefaultAgentLoop（完整 Loop 编排）
- [ ] MessageHistory（内存消息历史管理 + 超长裁剪）
- [ ] application.yaml 配置
- [ ] 端到端验证：`通过bash输出hello` 走通

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
| - | - | - |

## 决策记录

| 时间 | 决策 | Git Commit |
|------|------|------------|
| 2026-05-09 03:35 CST | Step 1 可以进入执行前准备；开工前补充 Spring AI Anthropic 集成方式、AgentState、StreamEvent、tool_use_id 四个细节。 | b39d7ef |
| 2026-05-09 12:15 CST | Step 1 执行决策：ChatMessage 使用 block-based 模型；Spring AI 负责配置和 Anthropic 接入，Cimo 自己掌控 Agent Loop 与 tool_result 协议；CLI 使用 Spring Shell；BashTool 先采用白名单命令；AgentState 命名统一为大写枚举。 | f1704a0 |
| 2026-05-09 12:23 CST | Step 1 进一步收窄：BashTool 白名单仅支持 echo；system prompt 通过 ClientRequest 独立传入；CLI 采用类似 Codex / Claude Code 的 JLine 交互式 REPL，而不是 Spring Shell 命令模式。 | 未提交 |
