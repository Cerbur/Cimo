package ai.cerbur.cimo.tool.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import ai.cerbur.cimo.tool.ToolExecutionContext;

/**
 * 文件类工具共享的安全边界，统一处理工作区限制、敏感路径、二进制文件和大小上限。
 *
 * <p>这个类不承担具体 read/write/edit/search 行为，只把所有文件工具必须遵守的
 * 共同约束固化为可测试 API，避免后续每个工具各自实现一套略有差异的校验。
 */
@Component
public class FileToolSecurity {

    public static final long READ_MAX_BYTES = 200L * 1024L;
    public static final long WRITE_MAX_BYTES = 1024L * 1024L;
    public static final long EDIT_MAX_BYTES = 1024L * 1024L;
    public static final int SEARCH_MAX_RESULTS = 100;
    public static final long SEARCH_MAX_OUTPUT_BYTES = 100L * 1024L;

    private static final int BINARY_PROBE_BYTES = 8192;
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".jar", ".class",
            ".ico", ".woff", ".woff2", ".ttf", ".otf", ".gz", ".tar", ".7z",
            ".exe", ".dll", ".so", ".dylib");

    /**
     * 将模型输入路径解析为工作区内的绝对归一化路径，并拒绝路径穿越与工作区外路径。
     */
    public Path resolveWorkspacePath(ToolExecutionContext context, String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new FileToolSecurityException("Path is required.");
        }

        Path workspace = normalizeExistingWorkspace(context.workingDirectory());
        Path rawPath = Path.of(userPath);
        Path candidate = rawPath.isAbsolute()
                ? rawPath.toAbsolutePath().normalize()
                : workspace.resolve(rawPath).normalize();

        if (!candidate.startsWith(workspace)) {
            throw new FileToolSecurityException("Path is outside workspace: " + userPath);
        }

        ensureExistingPathDoesNotEscapeWorkspace(workspace, candidate, userPath);
        return candidate;
    }

    /**
     * 校验读取目标必须是工作区内普通 UTF-8 文本文件，且不超过读取上限。
     */
    public void validateReadableTextFile(Path path) {
        validateExistingTextFile(path, READ_MAX_BYTES, "read");
    }

    /**
     * 校验编辑目标必须是工作区内普通 UTF-8 文本文件，且不超过编辑上限。
     */
    public void validateEditableTextFile(Path path) {
        validateExistingTextFile(path, EDIT_MAX_BYTES, "edit");
    }

    /**
     * 校验写入目标和内容大小；覆盖已有文件时同时按文本目标检查，防止覆盖敏感或二进制文件。
     */
    public void validateWritableTextPath(Path path, byte[] content, boolean overwrite) {
        validateSafePath(path);
        long contentBytes = content == null ? 0 : content.length;
        if (contentBytes > WRITE_MAX_BYTES) {
            throw new FileToolSecurityException("Write content exceeds 1048576 bytes: " + path);
        }
        if (hasBinaryExtension(path)) {
            throw new FileToolSecurityException("Binary target path is not allowed: " + path);
        }
        if (Files.exists(path)) {
            if (!overwrite) {
                throw new FileToolSecurityException("File already exists and overwrite is false: " + path);
            }
            validateExistingTextFile(path, WRITE_MAX_BYTES, "write");
        }
    }

    /**
     * 校验 list/search 的起点路径不能越过敏感目录；具体遍历深度和结果截断由对应工具负责。
     */
    public void validateBrowsablePath(Path path) {
        validateSafePath(path);
    }

    private Path normalizeExistingWorkspace(Path workingDirectory) {
        try {
            if (Files.exists(workingDirectory)) {
                return workingDirectory.toRealPath();
            }
        }
        catch (IOException ex) {
            throw new FileToolSecurityException("Failed to resolve workspace: " + ex.getMessage());
        }
        return workingDirectory.toAbsolutePath().normalize();
    }

    private void ensureExistingPathDoesNotEscapeWorkspace(Path workspace, Path candidate, String userPath) {
        if (!Files.exists(candidate)) {
            Path parent = candidate.getParent();
            if (parent != null && Files.exists(parent)) {
                ensureRealPathStartsWithWorkspace(workspace, parent, userPath);
            }
            return;
        }
        ensureRealPathStartsWithWorkspace(workspace, candidate, userPath);
    }

    private void ensureRealPathStartsWithWorkspace(Path workspace, Path path, String userPath) {
        try {
            if (!path.toRealPath().startsWith(workspace)) {
                throw new FileToolSecurityException("Path escapes workspace through symlink: " + userPath);
            }
        }
        catch (IOException ex) {
            throw new FileToolSecurityException("Failed to resolve path: " + userPath);
        }
    }

    private void validateExistingTextFile(Path path, long maxBytes, String operation) {
        validateSafePath(path);
        if (!Files.exists(path)) {
            throw new FileToolSecurityException("File does not exist for " + operation + ": " + path);
        }
        if (Files.isDirectory(path)) {
            throw new FileToolSecurityException("Directory is not allowed for " + operation + ": " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new FileToolSecurityException("Only regular files are allowed for " + operation + ": " + path);
        }
        long size = fileSize(path);
        if (size > maxBytes) {
            throw new FileToolSecurityException("File exceeds " + maxBytes + " bytes for " + operation + ": " + path);
        }
        if (hasBinaryExtension(path) || looksBinary(path)) {
            throw new FileToolSecurityException("Binary file is not allowed for " + operation + ": " + path);
        }
    }

    private void validateSafePath(Path path) {
        if (isSensitivePath(path)) {
            throw new FileToolSecurityException("Sensitive path is not allowed: " + path);
        }
    }

    private boolean isSensitivePath(Path path) {
        for (Path part : path) {
            String segment = part.toString().toLowerCase(Locale.ROOT);
            if (".git".equals(segment)
                    || segment.equals("id_rsa")
                    || segment.equals("id_ed25519")
                    || segment.equals(".env")
                    || segment.startsWith(".env.")
                    || segment.contains("secret")
                    || segment.contains("credential")
                    || segment.contains("token")) {
                return true;
            }
        }
        String fileName = fileNameLower(path);
        return fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || fileName.endsWith(".p12")
                || fileName.endsWith(".jks");
    }

    private boolean hasBinaryExtension(Path path) {
        String fileName = fileNameLower(path);
        return BINARY_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private boolean looksBinary(Path path) {
        byte[] bytes;
        try (var inputStream = Files.newInputStream(path)) {
            bytes = inputStream.readNBytes(BINARY_PROBE_BYTES);
        }
        catch (IOException ex) {
            throw new FileToolSecurityException("Failed to inspect file content: " + path);
        }
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return false;
        }
        catch (CharacterCodingException ex) {
            return true;
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        }
        catch (IOException ex) {
            throw new FileToolSecurityException("Failed to inspect file size: " + path);
        }
    }

    private String fileNameLower(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
    }
}
