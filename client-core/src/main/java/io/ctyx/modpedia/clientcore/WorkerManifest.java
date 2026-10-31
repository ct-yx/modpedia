package io.ctyx.modpedia.clientcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Pages 提供的 Worker 版本清单；不依赖 Gson 或 Minecraft。 */
public final class WorkerManifest {
    private final int schema;
    private final String channel;
    private final String workerVersion;
    private final int javaMajor;
    private final int protocolVersion;
    private final String primaryUrl;
    private final List<String> fallbackUrls;
    private final String sha256;
    private final String signature;

    public WorkerManifest(int schema, String channel, String workerVersion, int javaMajor,
                          int protocolVersion, String primaryUrl, List<String> fallbackUrls,
                          String sha256, String signature) {
        if (schema < 1) throw new IllegalArgumentException("schema 必须为正数");
        if (workerVersion == null || workerVersion.trim().isEmpty()) throw new IllegalArgumentException("worker_version 不能为空");
        if (javaMajor < 1) throw new IllegalArgumentException("java_major 无效");
        if (protocolVersion < 1) throw new IllegalArgumentException("protocol_version 无效");
        if (primaryUrl == null || primaryUrl.trim().isEmpty()) throw new IllegalArgumentException("artifact.primary_url 不能为空");
        this.schema = schema;
        this.channel = value(channel, "stable");
        this.workerVersion = workerVersion.trim();
        this.javaMajor = javaMajor;
        this.protocolVersion = protocolVersion;
        this.primaryUrl = primaryUrl.trim();
        List<String> urls = new ArrayList<String>();
        if (fallbackUrls != null) for (String url : fallbackUrls) if (url != null && !url.trim().isEmpty()) urls.add(url.trim());
        this.fallbackUrls = Collections.unmodifiableList(urls);
        this.sha256 = value(sha256, "").toLowerCase();
        if (!this.sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact.sha256 必须是 64 位十六进制摘要");
        }
        this.signature = value(signature, "");
        if (this.signature.isEmpty()) {
            throw new IllegalArgumentException("artifact.signature 不能为空");
        }
        try {
            java.util.Base64.getDecoder().decode(this.signature);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("artifact.signature 必须是 Base64", failure);
        }
    }

    public int schema() { return schema; }
    public String channel() { return channel; }
    public String workerVersion() { return workerVersion; }
    public int javaMajor() { return javaMajor; }
    public int protocolVersion() { return protocolVersion; }
    public String primaryUrl() { return primaryUrl; }
    public List<String> fallbackUrls() { return fallbackUrls; }
    public String sha256() { return sha256; }
    public String signature() { return signature; }

    public List<String> artifactUrls() {
        List<String> urls = new ArrayList<String>();
        urls.add(primaryUrl);
        urls.addAll(fallbackUrls);
        return Collections.unmodifiableList(urls);
    }

    /**
     * 生成发布端和客户端共同使用的 Ed25519 签名 payload。
     *
     * <p>格式固定为 UTF-8、LF 结尾的键值行，字段顺序为
     * schema、channel、worker_version、java_major、protocol_version、primary_url、
     * fallback_url（按清单顺序重复）和 sha256；signature 字段不参与签名。</p>
     */
    public byte[] signaturePayload() {
        StringBuilder payload = new StringBuilder();
        line(payload, "schema", Integer.toString(schema));
        line(payload, "channel", channel);
        line(payload, "worker_version", workerVersion);
        line(payload, "java_major", Integer.toString(javaMajor));
        line(payload, "protocol_version", Integer.toString(protocolVersion));
        line(payload, "primary_url", primaryUrl);
        for (String url : fallbackUrls) line(payload, "fallback_url", url);
        line(payload, "sha256", sha256);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String signaturePayloadText() {
        return new String(signaturePayload(), StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder payload, String name, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " 不能包含换行符");
        }
        payload.append(name).append('=').append(value).append('\n');
    }

    public String toJson() {
        return "{\"schema\":" + schema
                + ",\"channel\":\"" + JsonEscaper.escape(channel) + "\""
                + ",\"worker_version\":\"" + JsonEscaper.escape(workerVersion) + "\""
                + ",\"java_major\":" + javaMajor
                + ",\"protocol_version\":" + protocolVersion
                + ",\"artifact\":{\"primary_url\":\"" + JsonEscaper.escape(primaryUrl) + "\""
                + ",\"fallback_urls\":[" + joinUrls() + "]"
                + ",\"sha256\":\"" + JsonEscaper.escape(sha256) + "\""
                + ",\"signature\":\"" + JsonEscaper.escape(signature) + "\"}}";
    }

    public static WorkerManifest parse(String json) {
        Object parsed = JsonParser.parse(json);
        if (!(parsed instanceof java.util.Map)) throw new IllegalArgumentException("清单根节点必须是对象");
        java.util.Map<?, ?> root = (java.util.Map<?, ?>) parsed;
        java.util.Map<?, ?> artifact = object(root.get("artifact"), "artifact");
        return new WorkerManifest(number(root.get("schema"), "schema"), string(root.get("channel"), "channel"),
                string(root.get("worker_version"), "worker_version"), number(root.get("java_major"), "java_major"),
                number(root.get("protocol_version"), "protocol_version"), string(artifact.get("primary_url"), "artifact.primary_url"),
                strings(artifact.get("fallback_urls")), string(artifact.get("sha256"), "artifact.sha256"),
                string(artifact.get("signature"), "artifact.signature"));
    }

    private String joinUrls() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fallbackUrls.size(); i++) {
            if (i > 0) out.append(',');
            out.append('\"').append(JsonEscaper.escape(fallbackUrls.get(i))).append('\"');
        }
        return out.toString();
    }
    private static String value(String value, String fallback) { return value == null ? fallback : value.trim(); }
    private static String string(Object value, String name) {
        if (!(value instanceof String)) throw new IllegalArgumentException(name + " 必须是字符串");
        return (String) value;
    }
    private static int number(Object value, String name) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " 必须是数字");
        return ((Number) value).intValue();
    }
    private static java.util.Map<?, ?> object(Object value, String name) {
        if (!(value instanceof java.util.Map)) throw new IllegalArgumentException(name + " 必须是对象");
        return (java.util.Map<?, ?>) value;
    }
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (value == null) return result;
        if (!(value instanceof List)) throw new IllegalArgumentException("fallback_urls 必须是数组");
        for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
        return result;
    }
}
