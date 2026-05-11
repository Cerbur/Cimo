# Cimo Step 2 Plan

> Step 2 详细计划：最小工具集（read / list / search / write / edit / bash）以及 CLI 内置指令识别与分发。全局计划入口见 [plan_main.md](plan_main.md)，计划模板见 [plan_template.md](plan_template.md)。

---

## Step 2：工具集扩展 + CLI 内置指令

### 目标

在 Step 1 跑通 Agent Loop 的基础上，扩展更接近 Codex / Claude Code 的本地开发能力：文件读取、写入、编辑、搜索等工具；同时把 CLI 中不应进入 LLM 的本地指令抽象为独立识别与分发链路。

本阶段最终要让 Agent 可以通过工具读取、搜索并在受控范围内修改工作区文件；CLI 可以识别本地指令，例如 `/mcp`、`/compact`，并交给专门 handler 处理，而不是作为普通自然语言输入提交给 `AgentLoop`；`exit` / `quit` 这类退出指令的行为保持兼容，并纳入同一套指令处理边界。

Step 2 的工具集完成后，必须具备“基本编写代码能力”：Agent 能围绕一个小型、明确的代码变更任务，自主完成读取相关文件、搜索定位、编辑代码、运行本项目验证命令、根据失败信息再次修正这一最小闭环。单个工具是否完备，不能只看它自身 API 是否能跑通，还要看它是否支撑这条编码闭环。

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
- 编码能力约束：工具设计必须服务“基本编写代码能力”的闭环，而不是孤立完成 `read` / `list` / `search` / `write` / `edit` / `bash` 的点状实现；每个工具的范围、权限和验收都要回答它在读、搜、改、验、修中的职责。
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

### S2-07：Bash 工具权限模型

Claude Code 当前不是单纯白名单或黑名单模型，而是分层权限模型：只读工具默认可用；Bash、写文件等高风险工具默认需要确认；权限规则包含 `allow`、`ask`、`deny` 三类，且按 `deny -> ask -> allow` 顺序匹配，`deny` 永远优先。Bash 规则支持通配匹配，但官方文档也明确提醒：用字符串模式约束 Bash 参数是脆弱的，尤其是网络、变量、重定向、包装命令和复合命令场景。

#### 第一性原理

Bash 是本地 Agent 里风险最高、同时又最有价值的工具：它既能跑测试、查 git、执行构建，也能删除文件、泄露密钥、访问网络或修改工作区外内容。因此 Step 2 如果继续扩展工具安全边界，需要把 Bash 权限纳入同一套决策模型，而不是只靠 prompt 要求“不要执行危险命令”。

#### 设计约束

- 默认策略应接近“保守允许”：明确只读命令可直接执行；写入、网络、删除、进程控制、包安装、git push 等需要确认或拒绝。
- 规则模型至少能表达 `allow`、`ask`、`deny` 三种结果；匹配顺序为 deny 优先，其次 ask，最后 allow。
- 不把黑名单当作唯一安全机制；黑名单只能作为明确拒绝的补充，例如 `rm -rf /`、读取密钥、外部网络访问等。
- 不在 Step 2 引入完整 OS sandbox；但计划中必须承认权限规则与 sandbox 是两层不同防线，未来 Step 6 再做 OS 级隔离。
- Bash 复合命令需要按 shell 控制符拆分后逐段判断，不能因为前半段命中 allow 就放行整条命令。
- 对 `curl`、`wget`、`ssh`、`scp`、`nc`、包管理器安装、`git push` 等外部副作用命令，默认进入 ask 或 deny，而不是 allow。
- 对 `ls`、`pwd`、`cat`、`head`、`tail`、`rg`、`grep`、`find` 的安全子集、`git status`、`git diff`、`git log` 等只读命令，可以进入内置 allow 候选，但需要用测试固定边界。

#### 候选实现方向

最小实现路径：

1. 在现有 BashTool 执行前增加 `CommandPermissionPolicy` 或同等职责对象，输入原始命令，输出 `ALLOW / ASK / DENY`。
2. Step 2 先实现内置规则，不急于做用户可配置文件；配置化权限可作为后续增强。
3. 对 shell 控制符（例如 `&&`、`||`、`;`、`|`、换行）拆分后的每个 segment 独立判定；只要任一 segment 为 deny 则整条拒绝，任一 segment 为 ask 则整条需要确认。
4. 对需要确认的命令，CLI 先返回明确提示；是否实现交互确认需要和 CLI 指令 handler 一起收口。
5. 补充测试覆盖只读命令、危险命令、复合命令、网络命令、git push、路径穿越或工作区外写入等边界。

#### 分层权限设计草案

权限决策分三层，每一层只解决自己的真实问题：

1. **Tool 级别权限**：决定某类工具是否允许被模型调用，例如 `bash`、`read`、`write`、`edit`。这一层用于快速关闭高风险能力，不理解命令细节。
2. **Operation 级别权限**：理解工具参数后判断具体操作，例如 Bash 的 `git status`、`./gradlew test`、`curl ...`、`rm ...`。这一层是 Step 2 的核心，负责输出 `ALLOW / ASK / DENY`。
3. **Runtime 级别边界**：工作目录、超时、输出截断、环境变量、未来 OS sandbox。Step 2 只实现进程级基本约束；OS sandbox 后续 Step 6 规划。

候选核心类型：

```java
public enum PermissionDecision {
    ALLOW,
    ASK,
    DENY
}

public record PermissionCheck(
        String toolName,
        JsonNode arguments,
        String rawOperation) {
}

public record PermissionResult(
        PermissionDecision decision,
        String reason,
        List<String> matchedRules) {
}

public interface ToolPermissionPolicy {
    PermissionResult check(PermissionCheck check);
}
```

对 Bash 来说，`rawOperation` 是模型请求执行的命令字符串。Step 1 当前 schema 是 `command + args` 且只支持 `echo`；Step 2 需要在“保留结构化参数”和“改为 shell command 字符串”之间做取舍：

- **方案 A：继续结构化 `command + args`**  
  优点是更容易做权限判断和命令注入控制；缺点是不支持管道、重定向、`&&`、`./gradlew test --tests ...` 这类真实开发常用命令，Agent 能力会明显受限。
- **方案 B：改为 Claude Code 风格的 `command` 字符串**  
  优点是符合开发者预期，能表达真实 shell 工作流；缺点是必须实现 segment 拆分、风险分类、确认流程和更严格测试。

当前倾向：Step 2 采用 **方案 B**，但不裸奔执行。BashTool schema 暴露单个 `command` 字符串；执行前必须经过 `BashPermissionPolicy`，并且用 `timeout`、固定工作目录、输出截断和环境变量最小化约束兜底。

建议的内置规则分层：

| 层级 | 默认决策 | 示例 | 说明 |
|------|----------|------|------|
| Built-in deny | DENY | `rm -rf /`、`rm -rf ~`、读取 `.env` / `secrets`、`chmod -R 777 /` | 明确高危或明显越界，不进入确认 |
| Built-in read-only allow | ALLOW | `pwd`、`ls`、`cat`、`head`、`tail`、`rg`、`grep`、`wc`、`git status`、`git diff`、`git log` | 只读观察能力，减少无意义确认 |
| Project verification allow | ALLOW 或 ASK | `./gradlew test`、`./gradlew bootRun`、`npm test` | 是否默认 allow 需要结合本项目配置；Step 2 可先只 allow `./gradlew test` |
| Side-effect ask | ASK | `mkdir`、`touch`、`cp`、`mv`、`git add`、`git commit`、`./gradlew build` | 有本地副作用，但可能是开发任务必要动作 |
| External side-effect ask/deny | ASK 或 DENY | `git push`、`curl`、`wget`、`ssh`、`scp`、包安装 | 默认不自动放行；网络类命令后续应交给更专门的工具或 sandbox |
| Unknown | ASK | 未命中规则的命令 | 保守处理，不默默执行 |

复合命令聚合规则：

1. 先按 shell 控制符把命令拆成 segments，至少覆盖 `&&`、`||`、`;`、`|`、换行。
2. 每个 segment 独立判定。
3. 聚合时 `DENY` 优先；任一 segment 为 `DENY`，整条命令拒绝。
4. 没有 `DENY` 但存在 `ASK`，整条命令需要确认。
5. 所有 segment 都是 `ALLOW`，整条命令才可直接执行。

确认流程暂定：

- Step 2 初期可以先不做交互式 approve，而是对 `ASK` 返回工具错误：`Command requires approval: ...`，避免 Agent 在无人确认时继续执行。
- 如果本阶段决定实现确认，确认能力不应放在 BashTool 内部读取 stdin；应由 entry / command confirmation 层处理，避免工具层反向依赖 CLI。
- 后续可引入“本次会话允许一次 / 本项目允许前缀”的确认缓存，但 Step 2 不作为必要范围。

#### 基本编码能力对 Bash 的反推

BashTool 的允许范围必须能支撑最小编码闭环里的“验证”和“观察”：

- 能观察仓库状态：`pwd`、`ls`、`find`/`rg --files`、`git status`、`git diff`、`git log`。
- 能运行本项目验证：至少支持 `./gradlew test`；如后续 CLI 指令验收需要，支持受控的 `./gradlew bootRun` 手动验证。
- 能读取命令输出用于修复：测试失败输出、编译错误、栈信息不能被过度截断到失去定位价值；但仍需要最大输出限制，避免终端或上下文被刷爆。
- 不要求 Bash 承担文件编辑职责：文件修改应优先通过 Write/Edit 工具完成，Bash 不默认允许 `sed -i`、重定向写文件、`perl -pi` 这类绕过编辑工具的写入路径。
- 不要求 Bash 承担网络和发布职责：`curl`、`wget`、`git push`、包安装默认不属于“基本编码能力”的必要条件，除非后续单独计划确认。

因此，Bash 权限不是“越宽越像 coding agent”，而是必须覆盖编写代码所需的验证命令，同时把写入、发布、联网和破坏性操作继续交给更明确的工具或确认流程。

### S2-08：基本编写代码能力验收闭环

Step 2 的最终验收要包含一个小型真实编码任务，用来检查工具集合是否完整，而不是只分别测试工具函数。

#### 第一性原理

本项目目标是 Agent Harness。对本地开发 Agent 来说，“基本编写代码能力”不是生成一段文本，而是能在工作区内完成一轮可验证的代码变更：理解现有代码、定位相关文件、修改实现、运行验证、根据失败反馈迭代。缺少其中任一环，工具集都只是局部可用，还不能算 Step 2 完成。

#### 编码闭环定义

最小闭环包含：

1. **读**：能读取指定文件和相关上下文，理解当前实现。
2. **搜**：能按文件名、符号、文本内容搜索定位影响范围。
3. **改**：能以可控方式创建文件、覆盖文件或做局部编辑。
4. **验**：能运行项目内验证命令，例如 `./gradlew test`，并拿到足够输出。
5. **修**：验证失败时，能根据错误输出回到读 / 搜 / 改继续迭代。
6. **留痕**：执行后能在 plan 中记录 Todo 状态、改动摘要和验证结果。

#### 工具完备性检查矩阵

| 能力 | 依赖工具 | 完备性问题 |
|------|----------|------------|
| 读取相关代码 | Read / List | 是否能读取工作区内文本文件；是否拒绝工作区外路径和敏感文件 |
| 搜索定位 | Search / Bash 安全只读子集 | 是否能按文件名和内容定位；是否支持 `rg` 或等价能力 |
| 修改代码 | Write / Edit | 是否支持新建文件、局部替换、避免误覆盖；失败提示是否足够可修复 |
| 运行验证 | Bash | 是否允许 `./gradlew test`；是否有超时、工作目录、输出截断和错误返回 |
| 失败修复 | Read / Search / Edit / Bash | 失败输出是否能被模型利用；工具结果是否区分 success/error |
| 安全边界 | 所有文件工具 / Bash | 是否统一工作区根目录校验；是否防路径穿越；是否防 Bash 绕过编辑工具写入 |

#### 最小端到端验收场景

Step 2 Ready 前需要确定一个具体、低风险的验收任务；候选场景：

- 新增或调整一个纯函数 / 小型 formatter，并补充单元测试。
- 修复一个刻意放置的简单失败测试。
- 为已有 CLI 指令 handler 增加一个边界 case，并跑 `./gradlew test` 验证。

验收时 Agent 必须至少使用一次读/搜工具、一次 Edit 或 Write 工具、一次 Bash 验证命令，并在验证失败时能保留失败记录而不是标记 Done。

### S2-09：Step 2 最小 Tool 集合

Step 2 的 Tool 范围按“基本编写代码能力”闭环反推，而不是照搬 Codex / Claude Code 的完整工具列表。当前确认的最小 Tool 集合为：

| Tool | 必要性 | 最小职责 |
|------|--------|----------|
| `read` | 必需 | 读取工作区内指定文本文件，帮助 Agent 理解现有代码 |
| `list` | 必需 | 列出目录内容，帮助 Agent 发现文件结构和确认路径存在 |
| `search` | 必需 | 按文本、符号或路径搜索定位影响范围；优先覆盖原计划中 `Glob` 的最小需求 |
| `write` | 必需 | 新建文件，或在显式允许时覆盖完整文件 |
| `edit` | 必需 | 对已有文件做可校验的局部替换，避免小改动依赖整文件重写 |
| `bash` | 必需 | 运行受控观察与验证命令，例如 `git status`、`git diff`、`./gradlew test` |

#### 当前不进入最小集合的 Tool

- `glob`：暂不作为独立 Tool；先由 `search` 的路径搜索能力覆盖。只有后续证明通配模式搜索存在独立职责和验收收益时再引入。
- `multiedit`：先用单次 `edit` 覆盖最小局部修改场景；等出现真实批量编辑需求时再规划。
- `patch` / `apply_patch`：能力强但边界复杂，Step 2 先不引入补丁解析或三方 patch 语义。
- `delete` / `move` / `mkdir`：不是最小编码闭环必需能力，避免过早扩大文件破坏面。
- `web` / `fetch`：不属于本地开发最小闭环，且会扩大网络权限与安全边界。

#### Tool 拆分原则

- 每个最小 Tool 都使用独立 Todo 细化职责、schema、失败语义和验收标准。
- 文件类 Tool 共享工作区根目录校验、路径归一化、敏感文件拒绝、大文件/二进制保护等安全边界，但不为共享逻辑提前引入无法证明收益的重抽象。
- `search` 先承载文件名/路径搜索与内容搜索的最小能力，避免同时引入 `glob` 导致职责重叠。
- `bash` 的编辑、删除、联网、发布能力默认不作为编码闭环必要条件；文件修改优先通过 `write` / `edit` 完成。

---

## 任务拆解

| ID | Todo | 状态 | 验收标准 |
|----|------|------|----------|
| S2-T01 | 设计 CLI 内置指令识别与分发 handler，覆盖 `exit` / `quit`、预留 `/mcp`、`/compact` | Draft | `CliAgentEntry` 不再内联不断增长的指令判断；`exit` / `quit` 保持兼容；本地指令不会进入 `AgentLoop`；有单元测试覆盖已处理、未识别、退出请求三类结果 |
| S2-T02 | 固化 Step 2 最小 Tool 集合：`read` / `list` / `search` / `write` / `edit` / `bash` | Draft | plan 明确记录最小集合、每个 Tool 的必要性、暂不实现的 Tool 及原因；`glob` 不作为独立最小 Tool，先由 `search` 覆盖路径搜索需求 |
| S2-T03 | 明确工具安全边界：工作区根目录、路径穿越、防误写、覆盖策略 | Draft | 所有文件工具都只能在工作区根目录内操作；路径穿越被拒绝；写入/覆盖策略有明确错误提示和测试覆盖 |
| S2-T04 | 明确工具 schema 与 provider adapter 的边界，避免重新引入无职责 `ToolSpec` | Draft | 工具 schema 从 `Tool` 或明确的工具描述结构出发；provider adapter 只做 provider 协议转换；不会出现无法证明职责的中间抽象 |
| S2-T05 | 为文件工具补充单元测试和最小端到端验证 | Draft | 单元测试覆盖成功、失败、边界路径、覆盖策略；最小端到端能验证 Agent 通过工具读取/搜索/修改工作区文件 |
| S2-T06 | 增强 CLI 流式输出体验：保证 `TEXT_DELTA` 为新增文本、response 即时 flush、tool 输出换行边界稳定 | Draft | `TEXT_DELTA` 不重复输出累计全文；CLI 能即时 flush；response、tool call、tool result 不粘连；有测试或替身验证 delta 语义 |
| S2-T07 | 设计 Bash 工具权限模型：只读 allow、危险 deny、副作用 ask，覆盖复合命令拆分判断 | Draft | Bash 不依赖单纯黑名单；权限决策能表达 allow/ask/deny；deny 优先；复合命令按 segment 判定；危险命令和外部副作用命令有测试覆盖 |
| S2-T08 | 定义并验收 Step 2 基本编写代码能力闭环：读、搜、改、验、修、留痕 | Draft | 至少一个小型真实编码任务能通过工具闭环完成；验收覆盖 `read` / `list` / `search` / `edit` / `write` / `bash`；失败时能记录失败原因且不误标 Done |
| S2-T09 | 细化并实现 `read` Tool | Draft | 能读取工作区内文本文件；拒绝工作区外路径、路径穿越、目录、二进制或超限大文件、敏感文件；返回内容、路径和截断信息；有成功与失败测试 |
| S2-T10 | 细化并实现 `list` Tool | Draft | 能列出工作区内目录内容；默认非递归或有限深度；拒绝工作区外路径和文件路径误用；输出稳定、可读、可限制数量；有边界测试 |
| S2-T11 | 细化并实现 `search` Tool | Draft | 支持内容搜索和路径/文件名搜索的最小能力；可限制路径、结果数量和输出长度；能覆盖 Step 2 暂不独立实现 `glob` 的路径定位需求；有无结果、超限、路径边界测试 |
| S2-T12 | 细化并实现 `write` Tool | Draft | 支持新建工作区内文本文件；覆盖已有文件必须显式允许；拒绝工作区外路径、路径穿越、敏感文件和不安全覆盖；返回明确成功或失败信息；有覆盖策略测试 |
| S2-T13 | 细化并实现 `edit` Tool | Draft | 支持对已有文本文件做 `oldString -> newString` 的唯一匹配替换；找不到或多处匹配时失败并给出可修复提示；拒绝工作区外路径、敏感文件和二进制文件；有局部编辑测试 |
| S2-T14 | 细化并实现 Step 2 版 `bash` Tool | Draft | schema 收口为单个 `command` 字符串；执行前经过 Bash 权限策略；至少允许安全观察命令和 `./gradlew test`；有超时、固定工作目录、输出截断、ASK/DENY 返回测试 |

---

## 验收方式

- 必跑命令：
  - `./gradlew test`
- 涉及 CLI 指令时：
  - 启动 `./gradlew bootRun` 后验证 `exit` / `quit` 仍可退出。
  - 验证 `/mcp`、`/compact` 等已规划本地指令不会作为普通自然语言进入 `AgentLoop`；若暂未实现具体行为，应返回明确的本地提示。
- 涉及文件工具时：
  - 验证 `read` / `list` / `search` / `write` / `edit` 的成功路径与失败路径。
  - 验证路径穿越、工作区外访问、防误写或覆盖策略。
- 涉及基本编码能力时：
  - 使用一个低风险真实编码任务验证读、搜、改、验、修闭环。
  - 验证 BashTool 至少支持运行 `./gradlew test` 并返回可用于修复的失败输出。
  - 验证文件修改优先通过 Write/Edit 工具完成，不依赖 Bash 重定向或 `sed -i` 绕过编辑边界。
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
| Bash 命令误放行 | 只靠字符串黑名单或 prompt 约束，导致复合命令、网络命令、危险参数绕过 | 引入 deny/ask/allow 决策链；复合命令按 segment 判定；危险命令默认 ask/deny；未来 Step 6 再叠加 OS sandbox |
| 工具点状可用但不能编码 | 单个 Read/Edit/Bash 测试通过，但 Agent 无法完成读、搜、改、验、修闭环 | 把“基本编写代码能力”作为 Step 2 总体验收；每个工具验收都要映射到编码闭环 |
| Provider schema 边界漂移 | 为了适配 Anthropic 重新引入无职责 `ToolSpec` | 保持核心 `Tool` 为事实来源，adapter 只负责协议转换；若必须新增结构，先记录职责和收益 |
| 流式输出重复 | provider 返回累计文本但上游当增量渲染 | adapter 内部 diff 或隔离 helper 测试，确保上游只看到新增 delta |

---

## 关键决策记录（ADR-lite）

| 时间 | 决策 | 原因 | 备选方案 | 影响范围 | Git Commit |
|------|------|------|----------|----------|------------|
| 2026-05-10 02:15 CST | Step 2 记录 CLI 内置指令抽象需求：`CliAgentEntry.java:70-72` 当前内联处理 `exit/quit`，后续抽象 command handler 统一处理退出以及 `/mcp`、`/compact` 等本地指令；暂不进入编码。 | CLI 输入存在本地控制指令，不能全部进入 LLM；入口层不应持续膨胀。 | 继续在 `CliAgentEntry` 内联判断；引入完整命令框架。 | `entry` 包、未来 CLI 指令扩展。 | 924dd94 |
| 2026-05-10 02:47 CST | Step 2 记录流式输出体验增强：当前已有 `Client.chatStream()` 到 `System.out.print(...)` 的链路，后续重点是保证 provider adapter 的 `TEXT_DELTA` 语义为新增文本，并在 CLI 层做好即时 flush 与 response/tool 输出换行边界；不引入复杂 TUI 或强制逐字动画。 | 交互式 Agent 需要即时反馈；现有链路已足够，问题集中在 delta 语义和 CLI 输出边界。 | 重做渲染层；强制逐字动画；重做 provider 抽象。 | `client`、`agent`、`entry` 包。 | 924dd94 |
| 2026-05-11 CST | Step 2 按 plan 模板重写，所有 todo 使用稳定 ID，并补充状态、验收标准、允许/禁止修改范围、ADR-lite、风险与回滚。 | 让计划成为后续编码执行契约，减少 Agent 自行扩范围或丢失 todo 的风险。 | 继续使用自由文本 todo。 | `.plan/plan_step2.md`。 | 未提交 |
| 2026-05-11 CST | Step 2 记录 Bash 权限模型方向：参考 Claude Code 当前 `allow` / `ask` / `deny` 分层权限，Cimo 不采用单纯白名单或黑名单；默认只读命令可 allow，危险命令 deny，外部副作用命令 ask，且复合命令需拆分后逐段判断。 | Bash 同时具备最高价值和最高风险；字符串黑名单无法可靠覆盖变量、网络、复合命令和包装命令；需要可测试的权限决策链。 | 全量白名单；全量黑名单；完全依赖 prompt；Step 2 直接引入 OS sandbox。 | BashTool、安全策略、CLI 确认流程、测试。 | b447252 |
| 2026-05-11 CST | Step 2 增加“基本编写代码能力”作为总体验收和工具完备性检查标准：工具集必须支撑读、搜、改、验、修、留痕闭环；BashTool 的允许范围必须覆盖观察仓库状态和运行 `./gradlew test`，但不通过 Bash 默认承担文件编辑、网络或发布职责。 | Step 2 的目标是让 Agent 具备最小本地开发能力，而不是点状实现工具 API；Bash 权限必须反推自编码闭环所需能力。 | 只分别验收 Read/Edit/Bash 单工具；直接放宽 Bash 到任意命令；把编码闭环推迟到后续阶段。 | Step 2 总体验收、BashTool、文件工具、测试计划、执行记录。 | b447252 |
| 2026-05-11 21:57 CST | Step 2 最小 Tool 集合确定为 `read`、`list`、`search`、`write`、`edit`、`bash`，并为每个 Tool 设置独立 Todo；`glob` 暂不作为独立 Tool，由 `search` 的路径搜索能力覆盖。 | 最小集合直接服务读、搜、改、验、修、留痕闭环；`glob`、`multiedit`、`patch`、`delete`、`move`、`web/fetch` 当前不能证明是 Step 2 最小编码能力的必要条件。 | 照搬完整 Codex/Claude Code 工具集；保留独立 `glob`；把所有文件操作合并成单个万能 FileTool。 | `.plan/plan_step2.md`、后续 Tool schema、文件工具实现与测试拆分。 | 58951c1 |

---

## 执行记录

| 时间 | Todo ID | 操作 | 验证结果 | Git Commit |
|------|---------|------|----------|------------|

---

## 完成记录

| 完成时间 | Plan | Git Commit |
|---------|------|------------|
