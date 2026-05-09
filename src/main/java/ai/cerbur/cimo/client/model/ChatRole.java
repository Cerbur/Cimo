package ai.cerbur.cimo.client.model;

public enum ChatRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String anthropicName;

    ChatRole(String anthropicName) {
        this.anthropicName = anthropicName;
    }

    public String anthropicName() {
        return anthropicName;
    }
}
