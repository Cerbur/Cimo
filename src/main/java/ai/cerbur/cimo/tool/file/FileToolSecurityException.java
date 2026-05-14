package ai.cerbur.cimo.tool.file;

/**
 * 文件工具安全校验失败时抛出的异常，message 直接面向 ToolResult 错误输出。
 */
public class FileToolSecurityException extends RuntimeException {

    public FileToolSecurityException(String message) {
        super(message);
    }
}
