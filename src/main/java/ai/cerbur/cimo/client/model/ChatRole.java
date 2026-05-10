package ai.cerbur.cimo.client.model;

/**
 * 对话角色枚举，保留 provider 原生命名是为了让 adapter 转换时不散落字符串常量。
 */
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
