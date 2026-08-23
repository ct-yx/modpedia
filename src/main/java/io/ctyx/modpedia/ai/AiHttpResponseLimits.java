package io.ctyx.modpedia.ai;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** AI HTTP/SSE 响应的统一内存上限。 */
final class AiHttpResponseLimits {
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    static final int MAX_SSE_LINE_BYTES = 512 * 1024;
    static final int MAX_SSE_TOTAL_BYTES = 16 * 1024 * 1024;

    private AiHttpResponseLimits() {
    }

    static String read(InputStream input, int limit) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("HTTP 响应超过大小上限");
            }
            result.write(buffer, 0, read);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    static String readLine(BufferedReader reader, int limit) throws IOException {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n' || value == '\r') {
                break;
            }
            char character = (char) value;
            bytes += character <= 0x7F ? 1 : character <= 0x7FF ? 2 : 3;
            if (bytes > limit) {
                throw new IOException("SSE 事件行超过大小上限");
            }
            result.append(character);
        }
        if (value == -1 && result.isEmpty()) {
            return null;
        }
        return result.toString();
    }
}
