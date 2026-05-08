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
| CLI 入口（读 stdin + 事件打印） | 消息历史持久化 |
| Spring AI Anthropic starter 集成（真实 API 调用） | Session 管理 |
| BashTool 实现（可执行简单命令） | 多会话 |
| 完整 Agent Loop（LLM → tool_use → 执行 → 结果回传 → 最终回复） | 用户认证 |
| Spring Boot 启动即进入 CLI | Docker |

### 包结构

```
ai.cerbur.cimo
├── CimoApplication.java
│
├── entry                         ← 入口层：输入来源适配
│   ├── AgentEntry.java               — 接口：start / submitInput / shutdown
│   ├── CliAgentEntry.java            — CLI 实现（Scanner → 事件打印到 stdout）
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
│       └── BashTool.java            — Bash 工具（Step 1 唯一工具）
│
├── client                         ← LLM Provider 抽象
│   ├── Client.java                  — 接口：chatStream
│   ├── ClientFactory.java           — 工厂：读取配置创建
│   ├── model/
│   │   ├── ChatMessage.java         — 消息模型（含 toolCallId, toolUseId）
│   │   ├── ChatRole.java            — 角色枚举（user, assistant, tool）
│   │   └── StreamEvent.java         — 流式事件
│   ├── anthropic/
│   │   └── SpringAiAnthropicClient.java — Spring AI Anthropic starter 封装
│   └── openai/
│       └── OpenAiClient.java        — 占位
│
└── config
    ├── CimoProperties.java           — 配置属性（provider、api-key、model、work-dir）
    └── ToolConfig.java               — 工具安全配置（超时、禁止命令）
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

// ChatMessage: 统一消息模型
// - toolCallId: 当 role=assistant 且消息是 tool_use block 时，保存 id 用于后续 tool_result 匹配
// - toolUseId:  当 role=tool 且消息是 tool_result block 时，引用对应的 tool_use block id
public record ChatMessage(
    ChatRole role,
    String content,
    String toolCallId,   // tool_use 的 id（assistant → tool）
    String toolUseId     // tool_result 对应的 tool_use_id（tool → assistant）
) {}

public enum ChatRole { USER, ASSISTANT, TOOL }

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

public interface Client {
    Flux<StreamEvent> chatStream(
        List<ChatMessage> messages,
        List<Tool> tools
    );
}

// ClientFactory 通过 Spring AI 的 ChatClient.Builder 创建
// Spring AI Anthropic starter 自动配置 AnthropicChatModel
// ClientFactory 包装为 Cimo 内部的 Client 接口
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
      forbidden-commands: rm, shutdown, reboot, sudo, mkfs, dd
```

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
1. CliAgentEntry 读 stdin → AgentLoop.processInput("通过bash输出hello")
2. AgentLoop 构造 ChatMessage 列表（system prompt + user message）
3. Client.chatStream(messages, [BashTool描述]) → 请求 Anthropic
4. 解析流式响应：
   a. content_block_delta (text) → AgentEvent.Response (流式打印)
   b. content_block_start (tool_use) → stop text stream，记录 tool_use_id
5. ToolRegistry.getTool("bash") → BashTool.execute({command: "echo hello"})
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
- [ ] 明确 Anthropic 集成方式：通过 Spring AI 适配到自定义 Client 抽象
- [ ] CliAgentEntry（Scanner 读输入 + 事件输出）
- [ ] AgentEvent 模型（sealed class）
- [ ] AgentState 枚举定义（idle / running / waiting_tool / error / stopped）
- [ ] SpringAiAnthropicClient 流式调用 + tool_use 响应解析
- [ ] StreamEvent 支持 text_delta / tool_use_start / tool_input_delta / message_stop 等最小事件
- [ ] tool_use_id 在消息历史中保存，并用于后续 tool_result 回传
- [ ] BashTool（安全限制：超时 + 禁止命令）
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
