package ai.cerbur.cimo.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.cerbur.cimo.tool.ToolExecutionContext;

class FileToolSecurityTests {

    private final FileToolSecurity security = new FileToolSecurity();

    @TempDir
    Path workspace;

    @Test
    void resolvesRelativePathInsideWorkspace() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "hello");
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        Path resolved = security.resolveWorkspacePath(context, "hello.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("hello.txt").toRealPath());
    }

    @Test
    void rejectsPathTraversalOutsideWorkspace() {
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        assertThatThrownBy(() -> security.resolveWorkspacePath(context, "../outside.txt"))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("outside workspace");
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createTempDirectory("cimo-outside");
        Path outsideFile = Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("link.txt");
        Files.createSymbolicLink(link, outsideFile);
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        assertThatThrownBy(() -> security.resolveWorkspacePath(context, "link.txt"))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void rejectsMissingPathUnderSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createTempDirectory("cimo-outside-dir");
        Path link = workspace.resolve("linked-dir");
        Files.createSymbolicLink(link, outside);
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        assertThatThrownBy(() -> security.resolveWorkspacePath(context, "linked-dir/new/file.txt"))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void rejectsSensitivePathForRead() throws IOException {
        Path env = Files.writeString(workspace.resolve(".env.local"), "TOKEN=value");

        assertThatThrownBy(() -> security.validateReadableTextFile(env))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("Sensitive path");
    }

    @Test
    void rejectsGitInternalPathForBrowse() throws IOException {
        Path gitConfig = Files.createDirectories(workspace.resolve(".git")).resolve("config");

        assertThatThrownBy(() -> security.validateBrowsablePath(gitConfig))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("Sensitive path");
    }

    @Test
    void rejectsBinaryFileByNulByte() throws IOException {
        Path binary = workspace.resolve("data.txt");
        Files.write(binary, new byte[] {65, 0, 66});

        assertThatThrownBy(() -> security.validateReadableTextFile(binary))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("Binary file");
    }

    @Test
    void rejectsBinaryTargetByExtensionForWrite() {
        Path image = workspace.resolve("image.png");

        assertThatThrownBy(() -> security.validateWritableTextPath(image, "x".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("Binary target");
    }

    @Test
    void rejectsReadFileOverLimit() throws IOException {
        Path large = workspace.resolve("large.txt");
        Files.write(large, new byte[(int) FileToolSecurity.READ_MAX_BYTES + 1]);

        assertThatThrownBy(() -> security.validateReadableTextFile(large))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("exceeds 204800 bytes");
    }

    @Test
    void rejectsWriteContentOverLimit() {
        Path target = workspace.resolve("new.txt");

        assertThatThrownBy(() -> security.validateWritableTextPath(target,
                new byte[(int) FileToolSecurity.WRITE_MAX_BYTES + 1], false))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("exceeds 1048576 bytes");
    }

    @Test
    void rejectsOverwriteWhenNotExplicit() throws IOException {
        Path existing = Files.writeString(workspace.resolve("existing.txt"), "old");

        assertThatThrownBy(() -> security.validateWritableTextPath(existing,
                "new".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(FileToolSecurityException.class)
                .hasMessageContaining("overwrite is false");
    }

    @Test
    void allowsExplicitOverwriteOfTextFile() throws IOException {
        Path existing = Files.writeString(workspace.resolve("existing.txt"), "old");

        security.validateWritableTextPath(existing, "new".getBytes(StandardCharsets.UTF_8), true);

        assertThat(existing).exists();
    }
}
