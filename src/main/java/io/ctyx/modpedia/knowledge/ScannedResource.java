package io.ctyx.modpedia.knowledge;

import java.util.Map;

/** 一个从已安装模组资源中读取的手册来源。 */
public record ScannedResource(
        String modId,
        String modName,
        String version,
        String path,
        String sourceType,
        String content,
        String fingerprint,
        Map<String, String> translations
) {
    public ScannedResource {
        translations = translations == null ? Map.of() : Map.copyOf(translations);
    }
}
