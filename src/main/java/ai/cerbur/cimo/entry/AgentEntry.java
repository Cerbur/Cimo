package ai.cerbur.cimo.entry;

/**
 * 输入入口抽象，让 CLI、未来 API 或其他入口都能复用同一套 AgentLoop。
 */
public interface AgentEntry {
    void start();

    void submitInput(String input);

    void shutdown();
}
