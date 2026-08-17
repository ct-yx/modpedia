package io.ctyx.modpedia.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * 与加载器无关的知识源扫描结果。
 *
 * <p>Worker JVM 只能依赖这个纯 Java 数据载体，不能为了构造编译输入而加载
 * {@code LocalGuideScanner} 及其 NeoForge API。客户端扫描器和 Worker 扫描器都
 * 将自己的结果转换成这个类型后再交给 {@link KnowledgeCompiler}。</p>
 */
public record KnowledgeScanResult(
        List<ScannedResource> resources,
        List<String> warnings
) {
    public KnowledgeScanResult {
        resources = List.copyOf(Objects.requireNonNull(resources));
        warnings = List.copyOf(Objects.requireNonNull(warnings));
    }

}
