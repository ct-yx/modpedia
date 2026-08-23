package io.ctyx.modpedia.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 限制知识来源和派生输出只能访问指定根目录内的路径。 */
final class KnowledgePathGuard {
    private KnowledgePathGuard() {
    }

    static Path resolve(Path root, String relativePath) throws IOException {
        if (root == null || relativePath == null || relativePath.isBlank()) {
            throw new IOException("知识路径为空");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path input;
        try {
            input = Path.of(relativePath);
        } catch (RuntimeException exception) {
            throw new IOException("知识路径格式无效", exception);
        }
        if (input.isAbsolute()) {
            throw new IOException("知识路径必须是来源根目录内的相对路径");
        }
        Path candidate = normalizedRoot.resolve(input).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw new IOException("知识路径越过来源根目录");
        }
        rejectSymbolicLink(normalizedRoot, candidate);
        return candidate;
    }

    private static void rejectSymbolicLink(Path root, Path candidate) throws IOException {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("知识来源根目录不能是符号链接");
        }
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("知识路径不能经过符号链接");
            }
        }
    }
}
