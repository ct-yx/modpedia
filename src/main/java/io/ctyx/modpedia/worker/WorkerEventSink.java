package io.ctyx.modpedia.worker;

import com.google.gson.JsonObject;

import java.io.IOException;

/** Worker 内部向 JSONL 客户端发送事件的窄接口。 */
@FunctionalInterface
public interface WorkerEventSink {
    void send(JsonObject event) throws IOException;
}
