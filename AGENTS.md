# Cimo 开发代理入口规则

> 本文件只保留代理每次进入仓库时必须优先遵守的硬约束和索引。详细流程见 [.plan/agent_process.md](.plan/agent_process.md)，工程规范见 [.plan/engineering_rules.md](.plan/engineering_rules.md)，计划总入口见 [.plan/plan_main.md](.plan/plan_main.md)。

## 核心原则

- **先规划，后编码**：不急于写代码，先通过对话把 `.plan/` 下的计划内容迭代丰满。
- **留痕留史**：所有关键决策、plan 的完成都要记录时间和 git commit ID。
- **按 plan 行动**：只有我（用户）认为 plan 已经足够完善时，才开始根据 `.plan/` 中对应的计划进行编码工作。
- **第一性原理与奥卡姆剃刀**：所有设计必须先回答“这个抽象/文件/流程解决了什么真实问题”；如果不能证明其独立职责、当前必要性和可验证收益，就不引入或应删除，优先选择能完成目标的最简单设计。

## 当前工作阶段

- 当前处于 **Plan 迭代阶段**。
- 代理只能讨论需求、架构、技术选型，并持续丰富 `.plan/` 下对应 plan 文件的内容。
- 直到用户明确说「可以开始了」，才允许进入编码执行阶段。
- 详细流程、Plan 文件组织、能力型 Todo 细化要求、编码前后检查、Java QA 门禁和 Todo 冲突处理，统一见 [.plan/agent_process.md](.plan/agent_process.md)。

## 编码前硬检查

进入业务编码前，代理必须同时确认：

1. 用户已明确说「可以开始了」。
2. 对应 `.plan/plan_stepN.md` 的状态为 Ready。
3. 本次要实现的 Todo ID 明确。
4. 没有发现与其他 plan 的 todo 冲突。
5. 已读取相关 plan 和受影响代码。

如果以上任一条件不满足，代理只能继续计划迭代、提出问题或补充 plan，不得进入业务编码。

## 编码后硬检查

每次实现后，代理必须：

1. 更新对应 Todo 状态。
2. 记录实际改动摘要。
3. 记录执行过的验证命令及结果。
4. 若完成整个 plan，补充完成时间和 git commit ID。
5. 若验证失败，不得标记 Done，必须记录失败原因。

## Java 相关改动 QA 门禁

当本次改动包含 `.java`、`.kt`、`build.gradle`、`settings.gradle`、Spring 配置文件，或会影响 Java 运行逻辑的资源文件时，主 Agent 必须在标记 Todo Done 前调用 `.codex/agents/JavaReview` SubAgent。完整输入要求和通过条件见 [.plan/agent_process.md](.plan/agent_process.md)。

## 工程规范索引

以下长期工程规范必须遵守，完整内容见 [.plan/engineering_rules.md](.plan/engineering_rules.md)：

- 提示词集中维护在 `prompt` 包。
- 当前阶段采用 CLI 优先入口，`./gradlew bootRun` 启动后进入 `>` 提示符。
- Spring Bean 依赖默认使用成员变量上的 `@Autowired` 显式注入，存在真实初始化逻辑的构造器除外。
- 新增或修改代码时遵守中文注释约束和注释质量要求。

## 计划入口

- 总计划入口：[.plan/plan_main.md](.plan/plan_main.md)
- 当前详细计划：[.plan/plan_step2.md](.plan/plan_step2.md)
- QA 横向计划：[.plan/plan_qa.md](.plan/plan_qa.md)
