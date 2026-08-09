package io.ctyx.modpedia.search;

import java.util.Locale;

/** 搜索结果使用的语言过滤器。 */
public enum SearchLanguage {
    AUTO("auto"),
    ZH_CN("zh_cn"),
    EN_US("en_us"),
    NEUTRAL("neutral");

    private final String code;

    SearchLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SearchLanguage fromMinecraft(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return ZH_CN;
        }
        String normalized = languageCode.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("zh")) {
            return ZH_CN;
        }
        if (normalized.startsWith("en")) {
            return EN_US;
        }
        return EN_US;
    }
}
