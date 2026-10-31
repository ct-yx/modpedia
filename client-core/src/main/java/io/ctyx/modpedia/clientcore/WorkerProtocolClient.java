package io.ctyx.modpedia.clientcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 与 Worker v1/v2 握手和增量会话消息共用的 loader-neutral 协议模型。 */
public final class WorkerProtocolClient {
    public static final int LEGACY_VERSION = 1;
    public static final int CURRENT_VERSION = 2;
    public static final int MAX_JSONL_LINE_BYTES = 2 * 1024 * 1024;

    public static final class HelloChallenge {
        private final String challenge;
        private final int protocolVersion;
        public HelloChallenge(String challenge, int protocolVersion) {
            this.challenge = require(challenge, "challenge");
            this.protocolVersion = protocolVersion;
        }
        public String challenge() { return challenge; }
        public int protocolVersion() { return protocolVersion; }
    }

    public static final class HelloRequest {
        private final int protocolVersion;
        private final String adapter;
        private final List<String> capabilities;
        private final String proof;
        public HelloRequest(int protocolVersion, String adapter, List<String> capabilities, String proof) {
            this.protocolVersion = protocolVersion;
            this.adapter = require(adapter, "client_adapter");
            this.capabilities = immutable(capabilities);
            this.proof = require(proof, "challenge_proof");
        }
        public int protocolVersion() { return protocolVersion; }
        public String adapter() { return adapter; }
        public List<String> capabilities() { return capabilities; }
        public String proof() { return proof; }
    }

    public static final class ResumeRequest {
        private final String conversationId;
        private final long revision;
        public ResumeRequest(String conversationId, long revision) {
            this.conversationId = require(conversationId, "conversation_id");
            if (revision < 0) throw new IllegalArgumentException("revision 不能为负数");
            this.revision = revision;
        }
        public String conversationId() { return conversationId; }
        public long revision() { return revision; }
    }

    public static final class ConversationEvent {
        private final String conversationId;
        private final long revision;
        private final String type;
        private final String payload;
        public ConversationEvent(String conversationId, long revision, String type, String payload) {
            this.conversationId = require(conversationId, "conversation_id");
            if (revision < 1) throw new IllegalArgumentException("revision 必须从 1 开始");
            this.revision = revision;
            this.type = require(type, "event_type");
            this.payload = payload == null ? "" : payload;
        }
        public String conversationId() { return conversationId; }
        public long revision() { return revision; }
        public String type() { return type; }
        public String payload() { return payload; }
    }

    public static String challengeProof(String challenge, String token) {
        require(challenge, "challenge");
        require(token, "token");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((challenge + "\0" + token).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行时缺少 SHA-256", impossible);
        }
    }

    public static boolean accepts(int workerVersion, int clientVersion) {
        return workerVersion == LEGACY_VERSION || workerVersion == CURRENT_VERSION
                ? clientVersion == LEGACY_VERSION || clientVersion == CURRENT_VERSION
                : false;
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
    private static List<String> immutable(List<String> values) {
        if (values == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
