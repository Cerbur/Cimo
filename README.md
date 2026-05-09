# Cimo

Cimo 是一个从零开始构建的 Agent Harness 项目，会从一个小型 CLI Agent Loop 逐步演进为更完整的 Agent 运行时。

第一个里程碑是跑通命令行 Agent Loop：接入真实的 Anthropic 模型调用，并执行真实的 Bash 工具。项目会先从一条很窄但完整的纵向链路开始，再逐步扩展到更多工具、会话持久化、REST 管理层，以及独立的 Web 前端。

## 当前阶段

Cimo 当前处于规划阶段。

开发流程遵循 [Agents.md](Agents.md)：

- 先完善计划，再开始编码。
- 关键决策和计划完成记录都维护在 `plan.md` 中。
- 当某个计划项完成时，记录完成时间和对应的 Git commit ID。

## 路线图

1. CLI Agent Loop + Anthropic + BashTool
2. 更多工具集，例如 Read、Write、Edit、Glob
3. Session 管理与消息历史持久化
4. Harness 管理层与 REST API
5. 独立 Web 前端
6. 安全加固与 Docker 部署

详细实施计划见 [plan.md](plan.md)。

## 技术栈

- Java 25
- Spring Boot 4
- Gradle
- Spring AI / Anthropic 集成
- Spring Shell

## 开发

运行测试：

```bash
./gradlew test
```

运行应用：

```bash
./gradlew bootRun
```

## 许可证

本项目使用 MIT License，详见 [LICENSE](LICENSE)。
