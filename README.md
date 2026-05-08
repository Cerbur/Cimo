# Cimo

Cimo is an Agent Harness project built step by step from a small CLI loop into a fuller agent runtime.

The first milestone is a command-line Agent Loop that connects a real Anthropic model call with a real Bash tool execution. The project intentionally starts with a narrow vertical slice, then expands into more tools, session persistence, a REST management layer, and a separate web frontend.

## Current Stage

Cimo is currently in the planning stage.

Development follows the process in [Agents.md](Agents.md):

- Plan first, code after the plan is considered ready.
- Keep important decisions and completed plan records in `plan.md`.
- Record completion time and the related git commit ID when a plan item is finished.

## Roadmap

1. CLI Agent Loop + Anthropic + BashTool
2. More tools, such as Read, Write, Edit, and Glob
3. Session management and message history persistence
4. Harness management layer and REST API
5. Separate web frontend
6. Security hardening and Docker deployment

See [plan.md](plan.md) for the detailed implementation plan.

## Tech Stack

- Java 25
- Spring Boot 4
- Gradle
- Spring AI / Anthropic integration
- Spring Shell

## Development

Run tests:

```bash
./gradlew test
```

Run the application:

```bash
./gradlew bootRun
```

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
