package ai.cerbur.cimo.entry;

public interface AgentEntry {
    void start();

    void submitInput(String input);

    void shutdown();
}
