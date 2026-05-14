# Cimo Agent Process

> 本文件承载代理工作流程、Plan 组织规则和执行门禁。入口硬约束见 [../AGENTS.md](../AGENTS.md)，工程规范见 [engineering_rules.md](engineering_rules.md)。

## 工作流程

### 阶段一：Plan 迭代（当前阶段）

1. 通过对话讨论需求、架构、技术选型。
2. 持续丰富 `.plan/` 下对应 plan 文件的内容。
3. 直到用户说「可以开始了」才进入编码阶段。

### 能力型 Todo 的 Plan 细化要求

当 Todo 涉及 Tool、Agent Loop、Client adapter、CLI 交互、权限策略、输出协议等会被 LLM/Agent 直接使用的能力时，plan 阶段必须把“能力边界”细化到足以指导实现，不能只写宽泛验收语句。

此类 Todo 进入 Ready 前，至少需要在对应 plan 中明确：

1. **调用语义**：工具名/命令名、参数 schema、默认参数、可选参数、非法参数处理方式。
2. **能力边界**：递归深度、结果数量、输出长度、文件大小、超时、权限 allow/ask/deny 等硬上限或软上限；如果暂定默认值，必须说明为什么当前足够，以及后续扩大条件。
3. **输出契约**：成功输出格式、失败输出格式、是否稳定排序、是否包含元信息；关键输出需要给出最小 example，方便实现和测试对齐。
4. **典型使用路径**：从 Agent 视角说明它如何用该能力完成真实任务；例如从仓库根目录定位 Java 文件，需要几次 `list` / `search` 调用，默认值和上限是否会造成不必要的多轮调用。
5. **安全与性能策略**：路径归一化、敏感路径、symlink、二进制/大文件、遍历剪枝、结果截断等边界必须前置讨论；不能等实现或 review 阶段才首次决定。
6. **测试矩阵**：至少覆盖成功路径、边界值、越权/非法输入、截断/过滤行为、与相邻工具职责重叠处的行为。

如果编码执行中发现 plan 缺少上述关键细节，代理必须先暂停业务编码，回到 `.plan/` 补充决策记录和验收标准；只有用户确认或明确授权继续后，才能按补充后的 plan 实现。

### 阶段二：编码执行

1. 根据 `.plan/` 下对应 plan 文件的内容按序实现。
2. 每次实现后更新对应 plan 文件的进度。

## 编码前检查

进入编码前，代理必须确认：

1. 用户已明确说「可以开始了」。
2. 对应 `.plan/plan_stepN.md` 的状态为 Ready。
3. 本次要实现的 Todo ID 明确。
4. 没有发现与其他 plan 的 todo 冲突。
5. 已读取相关 plan 和受影响代码。

如果以上任一条件不满足，代理只能继续计划迭代、提出问题或补充 plan，不得进入业务编码。

## 编码后检查

每次实现后，代理必须：

1. 更新对应 Todo 状态。
2. 记录实际改动摘要。
3. 记录执行过的验证命令及结果。
4. 若完成整个 plan，补充完成时间和 git commit ID。
5. 若验证失败，不得标记 Done，必须记录失败原因。

## Java 相关改动 QA 门禁

- 当本次改动包含 `.java`、`.kt`、`build.gradle`、`settings.gradle`、Spring 配置文件，或会影响 Java 运行逻辑的资源文件时，主 Agent 必须在标记 Todo Done 前调用 `.codex/agents/JavaReview` SubAgent，执行其中提示词定义的 Plan 符合性、代码风格、代码逻辑和验证建议检查。
- 主 Agent 调用 JavaReview 时必须提供本次 Todo ID、对应 plan 文件路径、改动文件列表、git diff 或相关代码片段，以及已执行的验证命令和结果；如输入不完整，必须在 Todo 记录中说明原因和 JavaReview 的可检查范围。
- 若 JavaReview 输出“不通过”，不得将 Todo 标记为 Done；若输出“有条件通过”，主 Agent 必须处理或记录所有非阻塞问题后，才能继续完成 Todo 状态更新。

## Todo 冲突处理

- 如果某个 todo 与其他 plan 中已有 todo 冲突，必须先暂停并询问用户如何处理；由用户决定保留哪一个、移除哪一个，代理不得自行合并、删除或替换冲突 todo。

## Plan 文件组织

- 所有计划统一收口到 `.plan/` 文件夹下维护，避免根目录堆积多个 plan 文件。
- `.plan/plan_main.md` 是总计划入口，只维护全局状态、阶段索引、横向计划索引和跨阶段决策索引。
- `.plan/plan_stepN.md` 是分步计划文件；新建时优先从 `.plan/plan_template.md` 复制结构，并按实际阶段填写。
- `.plan/agent_process.md` 维护流程和门禁规则；`.plan/engineering_rules.md` 维护长期工程规范；`.plan/plan_template.md` 只维护可复制的计划骨架。
- 状态为 Done / Archived / Read-only 的 step 视为已归档，只允许阅读；除完成记录勘误、commit ID 补全等历史修正外，不得继续更新 todo、追加新范围或改写计划内容。
- 当某个阶段的内容膨胀到影响阅读时，优先拆出新的 `plan_stepN.md`，并在 `plan_main.md` 中保留链接和摘要。
- 根目录不再维护 `plan.md`；历史 `plan.md` 已迁移为 `.plan/plan_step1.md`。

## Plan 完成标记

当 `.plan/` 中的某个 plan 完成时，需要记录：

- 完成时间。
- 对应的 git commit ID。

记录位置使用对应计划文件的 `完成记录` 表格；表格骨架见 `.plan/plan_template.md`。
