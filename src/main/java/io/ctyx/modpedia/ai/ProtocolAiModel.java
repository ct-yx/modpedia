package io.ctyx.modpedia.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.ModelProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic Messages、OpenAI Responses 和 Gemini generateContent 的统一适配器。
 *
 * <p>LangChain4j 仍负责会话记忆、工具执行和上下文预算；这里仅负责把 ChatRequest
 * 转成各家的 JSON，并把响应恢复为 LangChain4j 的 AiMessage。这样三种协议都能沿用
 * 现有的 search_knowledge、计算工具、重试和历史会话链路。</p>
 */
public final class ProtocolAiModel implements ChatModel, StreamingChatModel {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = AiTokenBudget.DEFAULT_ANSWER;
    private static final int MAX_ERROR_BODY = 1200;

    private final AiSettings settings;
    private final HttpClient client;

    public ProtocolAiModel(AiSettings settings) {
        this.settings = settings == null ? AiSettings.defaults() : settings;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.settings.timeoutSeconds()))
                .build();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of();
    }

    @Override
    public ModelProvider provider() {
        return switch (settings.apiFormat()) {
            case NATIVE_MESSAGES -> ModelProvider.ANTHROPIC;
            case GENERATE_CONTENT -> ModelProvider.GOOGLE_AI_GEMINI;
            case CHAT_COMPLETIONS, RESPONSES -> ModelProvider.OTHER;
        };
    }

    @Override
    public List<ChatModelListener> listeners() {
        return List.of();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().modelName(settings.model()).build();
    }

    /** 设置页连接测试和模型列表共用的协议端点构造。 */
    static URI endpointFor(AiSettings settings, boolean streaming) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        return new ProtocolAiModel(actual).endpoint(actual, streaming, null);
    }

    static URI modelsEndpointFor(AiSettings settings) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        String base = stripTrailingSlash(AiClient.normalizedEndpoint(
                actual.endpoint(), actual.apiFormat()
        ));
        return URI.create(base + "/models");
    }

    static HttpRequest.Builder authenticatedBuilder(
            AiSettings settings,
            URI uri,
            boolean streaming
    ) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(actual.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream, application/json" : "application/json");
        switch (actual.apiFormat()) {
            case NATIVE_MESSAGES -> {
                builder.header("x-api-key", actual.effectiveApiKey());
                builder.header("anthropic-version", "2023-06-01");
            }
            case GENERATE_CONTENT -> builder.header("x-goog-api-key", actual.effectiveApiKey());
            case CHAT_COMPLETIONS, RESPONSES ->
                    builder.header("Authorization", "Bearer " + actual.effectiveApiKey());
        }
        return builder;
    }

    static JsonObject minimalPayload(AiSettings settings, boolean streaming) {
        AiSettings actual = settings == null ? AiSettings.defaults() : settings;
        JsonObject payload = new JsonObject();
        switch (actual.apiFormat()) {
            case NATIVE_MESSAGES -> {
                payload.addProperty("model", actual.model());
                payload.addProperty("max_tokens", AiTokenBudget.CONNECTION_TEST);
                JsonArray messages = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                user.addProperty("content", "Reply only with OK.");
                messages.add(user);
                payload.add("messages", messages);
                payload.addProperty("stream", streaming);
            }
            case RESPONSES -> {
                payload.addProperty("model", actual.model());
                JsonArray input = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "input_text");
                text.addProperty("text", "Reply only with OK.");
                content.add(text);
                user.add("content", content);
                input.add(user);
                payload.add("input", input);
                payload.addProperty("max_output_tokens", AiTokenBudget.CONNECTION_TEST);
                payload.addProperty("stream", streaming);
            }
            case GENERATE_CONTENT -> {
                JsonArray contents = new JsonArray();
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                JsonArray parts = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("text", "Reply only with OK.");
                parts.add(text);
                user.add("parts", parts);
                contents.add(user);
                payload.add("contents", contents);
                JsonObject generation = new JsonObject();
                generation.addProperty("maxOutputTokens", AiTokenBudget.CONNECTION_TEST);
                payload.add("generationConfig", generation);
            }
            case CHAT_COMPLETIONS -> throw new IllegalStateException("Chat Completions 不使用协议适配器");
        }
        return payload;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        AiSettings actual = settings;
        URI uri = endpoint(actual, false, request);
        JsonObject payload = payload(request, false);
        HttpResponse<String> response;
        try {
            response = client.send(
                    request(uri, payload, false),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("AI 请求连接失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("AI 请求已取消");
        }
        String body = response.body() == null ? "" : response.body();
        ensureSuccess(response.statusCode(), body);
        return parseResponse(parseObject(body), body);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        if (handler == null) {
            return;
        }
        StreamingHandleImpl handle = new StreamingHandleImpl();
        handle.thread = Thread.currentThread();
        // 让 LangChain4j 在首个工具事件前也能拿到取消句柄。
        handler.onPartialResponse(new PartialResponse(" "), new PartialResponseContext(handle));
        try {
            URI uri = endpoint(settings, true, request);
            HttpResponse<InputStream> response = client.send(
                    request(uri, payload(request, true), true),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body;
                try (InputStream input = response.body()) {
                    body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                ensureSuccess(response.statusCode(), body);
                return;
            }

            StreamAccumulator accumulator = new StreamAccumulator(settings.apiFormat());
            try (InputStream input = response.body(); BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            )) {
                String line;
                while (!handle.isCancelled() && (line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith(":")) {
                        continue;
                    }
                    String data = line.startsWith("data:") ? line.substring(5).strip() : line.strip();
                    if (data.isBlank() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonObject event = parseObject(data);
                    if (event == null) {
                        continue;
                    }
                    String delta = accumulator.accept(event);
                    if (!delta.isBlank()) {
                        handler.onPartialResponse(
                                new PartialResponse(delta),
                                new PartialResponseContext(handle)
                        );
                    }
                }
            }
            if (handle.isCancelled()) {
                return;
            }
            handler.onCompleteResponse(accumulator.response());
        } catch (CancellationException exception) {
            if (!handle.isCancelled()) {
                handler.onError(exception);
            }
        } catch (Throwable failure) {
            if (!handle.isCancelled()) {
                handler.onError(failure);
            }
        }
    }

    private HttpRequest request(URI uri, JsonObject payload, boolean streaming) {
        HttpRequest.Builder builder = authenticatedBuilder(settings, uri, streaming)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        return builder.build();
    }

    private URI endpoint(AiSettings settings, boolean streaming, ChatRequest request) {
        String base = stripTrailingSlash(AiClient.normalizedEndpoint(
                settings.endpoint(), settings.apiFormat()
        ));
        String model = request == null || request.modelName() == null || request.modelName().isBlank()
                ? settings.model()
                : request.modelName();
        try {
            return switch (settings.apiFormat()) {
                case NATIVE_MESSAGES -> URI.create(base + "/messages");
                case RESPONSES -> URI.create(base + "/responses");
                case GENERATE_CONTENT -> {
                    String modelPath = model == null ? "" : model.strip();
                    if (modelPath.startsWith("models/")) {
                        modelPath = modelPath.substring("models/".length());
                    }
                    // 模型名是路径段；保留常见的点、短横线和冒号，其余字符编码。
                    modelPath = encodePathSegment(modelPath);
                    String suffix = streaming ? ":streamGenerateContent?alt=sse" : ":generateContent";
                    yield URI.create(base + "/models/" + modelPath + suffix);
                }
                case CHAT_COMPLETIONS -> URI.create(base + "/chat/completions");
            };
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API 地址格式无效", exception);
        }
    }

    private JsonObject payload(ChatRequest request, boolean streaming) {
        ChatRequest actual = request == null ? ChatRequest.builder().build() : request;
        return switch (settings.apiFormat()) {
            case NATIVE_MESSAGES -> nativePayload(actual, streaming);
            case RESPONSES -> responsesPayload(actual, streaming);
            case GENERATE_CONTENT -> generateContentPayload(actual, streaming);
            case CHAT_COMPLETIONS -> throw new IllegalStateException("Chat Completions 应使用 LangChain4j 原生模型");
        };
    }

    private JsonObject nativePayload(ChatRequest request, boolean streaming) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName(request));
        payload.addProperty("max_tokens", maxOutputTokens(request));
        String system = systemText(request.messages());
        if (!system.isBlank()) {
            payload.addProperty("system", system);
        }
        payload.add("messages", nativeMessages(request.messages()));
        if (!request.toolSpecifications().isEmpty()) {
            payload.add("tools", nativeTools(request.toolSpecifications()));
            addNativeToolChoice(payload, request.toolChoice());
        }
        if (request.temperature() != null) {
            payload.addProperty("temperature", request.temperature());
        }
        payload.addProperty("stream", streaming);
        return payload;
    }

    private JsonArray nativeMessages(List<ChatMessage> messages) {
        JsonArray result = new JsonArray();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof ToolExecutionResultMessage tool) {
                JsonObject value = new JsonObject();
                value.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_result");
                block.addProperty("tool_use_id", tool.id());
                block.addProperty("content", tool.text());
                content.add(block);
                value.add("content", content);
                result.add(value);
                continue;
            }
            JsonObject value = new JsonObject();
            value.addProperty("role", message instanceof AiMessage ? "assistant" : "user");
            JsonArray content = new JsonArray();
            if (!textOf(message).isBlank()) {
                content.add(textBlock("text", textOf(message)));
            }
            if (message instanceof AiMessage ai) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "tool_use");
                    block.addProperty("id", call.id());
                    block.addProperty("name", call.name());
                    block.add("input", parseArguments(call.arguments()));
                    content.add(block);
                }
            }
            if (content.size() == 0) {
                content.add(textBlock("text", ""));
            }
            value.add("content", content);
            result.add(value);
        }
        return result;
    }

    private JsonArray nativeTools(List<ToolSpecification> specifications) {
        JsonArray result = new JsonArray();
        for (ToolSpecification specification : specifications) {
            JsonObject source = parseObject(specification.toJson());
            JsonObject tool = new JsonObject();
            tool.addProperty("name", specification.name());
            tool.addProperty("description", specification.description() == null ? "" : specification.description());
            tool.add("input_schema", source == null || !source.has("parameters")
                    ? new JsonObject() : source.get("parameters"));
            result.add(tool);
        }
        return result;
    }

    private void addNativeToolChoice(JsonObject payload, ToolChoice choice) {
        if (choice == ToolChoice.REQUIRED) {
            JsonObject value = new JsonObject();
            value.addProperty("type", "any");
            payload.add("tool_choice", value);
        } else if (choice == ToolChoice.NONE) {
            JsonObject value = new JsonObject();
            value.addProperty("type", "none");
            payload.add("tool_choice", value);
        } else if (choice == ToolChoice.AUTO) {
            JsonObject value = new JsonObject();
            value.addProperty("type", "auto");
            payload.add("tool_choice", value);
        }
    }

    private JsonObject responsesPayload(ChatRequest request, boolean streaming) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName(request));
        String system = systemText(request.messages());
        if (!system.isBlank()) {
            payload.addProperty("instructions", system);
        }
        payload.add("input", responsesInput(request.messages()));
        if (!request.toolSpecifications().isEmpty()) {
            payload.add("tools", responsesTools(request.toolSpecifications()));
            addSimpleToolChoice(payload, request.toolChoice());
        }
        payload.addProperty("max_output_tokens", maxOutputTokens(request));
        if (request.temperature() != null) {
            payload.addProperty("temperature", request.temperature());
        }
        payload.addProperty("stream", streaming);
        return payload;
    }

    private JsonArray responsesInput(List<ChatMessage> messages) {
        JsonArray result = new JsonArray();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof ToolExecutionResultMessage tool) {
                JsonObject output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", tool.id());
                output.addProperty("output", tool.text());
                result.add(output);
                continue;
            }
            if (message instanceof AiMessage ai && !ai.toolExecutionRequests().isEmpty()) {
                if (!textOf(message).isBlank()) {
                    result.add(responsesMessage("assistant", textOf(message)));
                }
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("type", "function_call");
                    value.addProperty("call_id", call.id());
                    value.addProperty("name", call.name());
                    value.addProperty("arguments", call.arguments());
                    result.add(value);
                }
                continue;
            }
            result.add(responsesMessage(message instanceof AiMessage ? "assistant" : "user", textOf(message)));
        }
        return result;
    }

    private JsonObject responsesMessage(String role, String text) {
        JsonObject value = new JsonObject();
        value.addProperty("role", role);
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "user".equals(role) ? "input_text" : "output_text");
        item.addProperty("text", text == null ? "" : text);
        content.add(item);
        value.add("content", content);
        return value;
    }

    private JsonArray responsesTools(List<ToolSpecification> specifications) {
        JsonArray result = new JsonArray();
        for (ToolSpecification specification : specifications) {
            JsonObject source = parseObject(specification.toJson());
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", specification.name());
            tool.addProperty("description", specification.description() == null ? "" : specification.description());
            tool.add("parameters", source == null || !source.has("parameters")
                    ? new JsonObject() : source.get("parameters"));
            if (specification.strict() != null) {
                tool.addProperty("strict", specification.strict());
            }
            result.add(tool);
        }
        return result;
    }

    private void addSimpleToolChoice(JsonObject payload, ToolChoice choice) {
        if (choice == ToolChoice.REQUIRED) {
            payload.addProperty("tool_choice", "required");
        } else if (choice == ToolChoice.NONE) {
            payload.addProperty("tool_choice", "none");
        } else if (choice == ToolChoice.AUTO) {
            payload.addProperty("tool_choice", "auto");
        }
    }

    private JsonObject generateContentPayload(ChatRequest request, boolean streaming) {
        JsonObject payload = new JsonObject();
        payload.add("contents", generateContents(request.messages()));
        String system = systemText(request.messages());
        if (!system.isBlank()) {
            JsonObject instruction = new JsonObject();
            instruction.add("parts", parts(textPart(system)));
            payload.add("systemInstruction", instruction);
        }
        if (!request.toolSpecifications().isEmpty()) {
            JsonObject declarations = new JsonObject();
            JsonArray functions = new JsonArray();
            for (ToolSpecification specification : request.toolSpecifications()) {
                JsonObject source = parseObject(specification.toJson());
                JsonObject function = new JsonObject();
                function.addProperty("name", specification.name());
                function.addProperty("description", specification.description() == null ? "" : specification.description());
                function.add("parametersJsonSchema", source == null || !source.has("parameters")
                        ? new JsonObject() : source.get("parameters"));
                functions.add(function);
            }
            declarations.add("functionDeclarations", functions);
            JsonArray tools = new JsonArray();
            tools.add(declarations);
            payload.add("tools", tools);
            if (request.toolChoice() == ToolChoice.REQUIRED) {
                JsonObject config = new JsonObject();
                JsonObject functionCallingConfig = new JsonObject();
                functionCallingConfig.addProperty("mode", "ANY");
                config.add("functionCallingConfig", functionCallingConfig);
                payload.add("toolConfig", config);
            } else if (request.toolChoice() == ToolChoice.NONE) {
                JsonObject config = new JsonObject();
                JsonObject functionCallingConfig = new JsonObject();
                functionCallingConfig.addProperty("mode", "NONE");
                config.add("functionCallingConfig", functionCallingConfig);
                payload.add("toolConfig", config);
            }
        }
        JsonObject generation = new JsonObject();
        if (request.temperature() != null) {
            generation.addProperty("temperature", request.temperature());
        }
        if (request.maxOutputTokens() != null) {
            generation.addProperty("maxOutputTokens", request.maxOutputTokens());
        }
        payload.add("generationConfig", generation);
        return payload;
    }

    private JsonArray generateContents(List<ChatMessage> messages) {
        JsonArray result = new JsonArray();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            JsonObject value = new JsonObject();
            value.addProperty("role", message instanceof AiMessage ? "model" : "user");
            JsonArray parts = new JsonArray();
            if (!textOf(message).isBlank()) {
                parts.add(textPart(textOf(message)));
            }
            if (message instanceof AiMessage ai) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    JsonObject part = new JsonObject();
                    JsonObject functionCall = new JsonObject();
                    functionCall.addProperty("name", call.name());
                    functionCall.add("args", parseArguments(call.arguments()));
                    part.add("functionCall", functionCall);
                    parts.add(part);
                }
            }
            if (message instanceof ToolExecutionResultMessage tool) {
                value.addProperty("role", "user");
                JsonObject part = new JsonObject();
                JsonObject functionResponse = new JsonObject();
                functionResponse.addProperty("name", tool.toolName());
                JsonObject response = new JsonObject();
                response.addProperty("result", tool.text());
                functionResponse.add("response", response);
                part.add("functionResponse", functionResponse);
                parts.add(part);
            }
            if (parts.size() == 0) {
                parts.add(textPart(""));
            }
            value.add("parts", parts);
            result.add(value);
        }
        return result;
    }

    private ChatResponse parseResponse(JsonObject root, String rawBody) {
        if (root == null) {
            throw new IllegalStateException("AI 返回的不是 JSON：" + abbreviate(rawBody));
        }
        return switch (settings.apiFormat()) {
            case NATIVE_MESSAGES -> parseNativeResponse(root);
            case RESPONSES -> parseResponsesResponse(root);
            case GENERATE_CONTENT -> parseGenerateResponse(root);
            case CHAT_COMPLETIONS -> throw new IllegalStateException("协议适配器不处理 Chat Completions");
        };
    }

    private ChatResponse parseNativeResponse(JsonObject root) {
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        JsonElement content = root.get("content");
        if (content != null && content.isJsonArray()) {
            for (JsonElement element : content.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject block = element.getAsJsonObject();
                String type = string(block, "type");
                if ("text".equals(type)) text.append(string(block, "text"));
                if ("tool_use".equals(type)) {
                    calls.add(toolCall(
                            string(block, "id"),
                            string(block, "name"),
                            jsonString(block.get("input"))
                    ));
                }
            }
        }
        return response(root, text.toString(), calls,
                calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_EXECUTION,
                usage(root.getAsJsonObject("usage")));
    }

    private ChatResponse parseResponsesResponse(JsonObject root) {
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        boolean hasOutputText = root.has("output_text") && !string(root, "output_text").isBlank();
        if (hasOutputText) text.append(string(root, "output_text"));
        JsonElement output = root.get("output");
        if (output != null && output.isJsonArray()) {
            for (JsonElement element : output.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if ("function_call".equals(string(item, "type"))) {
                    calls.add(toolCall(
                            firstString(item, "call_id", "id"),
                            string(item, "name"),
                            string(item, "arguments")
                    ));
                }
                JsonElement content = item.get("content");
                if (!hasOutputText && content != null && content.isJsonArray()) {
                    for (JsonElement part : content.getAsJsonArray()) {
                        if (part.isJsonObject() && "output_text".equals(string(part.getAsJsonObject(), "type"))) {
                            text.append(string(part.getAsJsonObject(), "text"));
                        }
                    }
                }
            }
        }
        return response(root, text.toString(), calls,
                calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_EXECUTION,
                usage(root.getAsJsonObject("usage")));
    }

    private ChatResponse parseGenerateResponse(JsonObject root) {
        StreamAccumulator accumulator = new StreamAccumulator(AiApiFormat.GENERATE_CONTENT);
        accumulator.accept(root);
        return accumulator.response();
    }

    private ChatResponse response(
            JsonObject root,
            String text,
            List<ToolExecutionRequest> calls,
            FinishReason reason,
            TokenUsage tokenUsage
    ) {
        AiMessage message = calls.isEmpty() ? AiMessage.from(text) : AiMessage.from(text, calls);
        return ChatResponse.builder()
                .aiMessage(message)
                .id(firstString(root, "id", "response_id"))
                .modelName(firstString(root, "model", "modelVersion"))
                .finishReason(reason)
                .tokenUsage(tokenUsage)
                .build();
    }

    private static TokenUsage usage(JsonObject value) {
        if (value == null) return null;
        int input = integer(value, "input_tokens", integer(value, "prompt_tokens", 0));
        int output = integer(value, "output_tokens", integer(value, "completion_tokens", 0));
        int total = integer(value, "total_tokens", input + output);
        return input == 0 && output == 0 && total == 0 ? null : new TokenUsage(input, output, total);
    }

    private static String systemText(List<ChatMessage> messages) {
        StringBuilder result = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage system) {
                if (!result.isEmpty()) result.append('\n');
                result.append(system.text());
            }
        }
        return result.toString();
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage system) return system.text();
        if (message instanceof UserMessage user) {
            return user.contents().stream()
                    .filter(content -> content instanceof TextContent)
                    .map(content -> ((TextContent) content).text())
                    .reduce("", (left, right) -> left + right);
        }
        if (message instanceof AiMessage ai) return ai.text() == null ? "" : ai.text();
        if (message instanceof ToolExecutionResultMessage tool) return tool.text();
        return "";
    }

    private static JsonObject textBlock(String type, String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", type);
        block.addProperty("text", text == null ? "" : text);
        return block;
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text == null ? "" : text);
        return part;
    }

    private static JsonArray parts(JsonObject part) {
        JsonArray array = new JsonArray();
        array.add(part);
        return array;
    }

    private static JsonElement parseArguments(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value == null || value.isBlank() ? "{}" : value);
            return parsed == null ? new JsonObject() : parsed;
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static String jsonString(JsonElement value) {
        return value == null || value.isJsonNull() ? "{}" : value.toString();
    }

    private static ToolExecutionRequest toolCall(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id == null || id.isBlank() ? "call-" + System.nanoTime() : id)
                .name(name == null ? "" : name)
                .arguments(arguments == null || arguments.isBlank() ? "{}" : arguments)
                .build();
    }

    private static String modelName(ChatRequest request) {
        return request == null || request.modelName() == null || request.modelName().isBlank()
                ? "" : request.modelName();
    }

    private static int maxOutputTokens(ChatRequest request) {
        return request != null && request.maxOutputTokens() != null
                ? request.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
    }

    private static void ensureSuccess(int status, String body) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + abbreviate(body));
        }
    }

    private static JsonObject parseObject(String value) {
        try {
            JsonElement element = JsonParser.parseString(value == null ? "" : value);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try { return object.get(name).getAsString(); } catch (RuntimeException ignored) { return ""; }
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            String value = string(object, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static int integer(JsonObject object, String name, int fallback) {
        try { return object != null && object.has(name) ? object.get(name).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= MAX_ERROR_BODY ? normalized : normalized.substring(0, MAX_ERROR_BODY) + "…";
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String encodePathSegment(String value) {
        String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20").replace("%2F", "/");
    }

    private static final class StreamingHandleImpl implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread thread;

        @Override public void cancel() {
            if (cancelled.compareAndSet(false, true) && thread != null) thread.interrupt();
        }

        @Override public boolean isCancelled() { return cancelled.get(); }
    }

    /** 累积 SSE/JSON 分片；文本实时交给 UI，工具参数在完整回合结束后一次性恢复。 */
    private static final class StreamAccumulator {
        private final AiApiFormat format;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, MutableToolCall> calls = new LinkedHashMap<>();
        /** Responses SSE 用 item_id 传参、最终工具结果却需要 call_id；两者指向同一调用。 */
        private final Map<String, MutableToolCall> callAliases = new LinkedHashMap<>();
        private String id = "";
        private String model = "";

        private StreamAccumulator(AiApiFormat format) { this.format = format; }

        private String accept(JsonObject event) {
            return switch (format) {
                case NATIVE_MESSAGES -> acceptNative(event);
                case RESPONSES -> acceptResponses(event);
                case GENERATE_CONTENT -> acceptGenerate(event);
                case CHAT_COMPLETIONS -> "";
            };
        }

        private String acceptNative(JsonObject event) {
            String type = string(event, "type");
            if ("message_start".equals(type) && event.has("message")) {
                JsonObject message = event.getAsJsonObject("message");
                id = string(message, "id");
                model = string(message, "model");
            }
            if ("content_block_start".equals(type)) {
                JsonObject block = event.getAsJsonObject("content_block");
                if (block != null && "tool_use".equals(string(block, "type"))) {
                    MutableToolCall call = new MutableToolCall(string(block, "id"), string(block, "name"));
                    calls.put(call.id, call);
                }
            }
            if ("content_block_delta".equals(type)) {
                JsonObject delta = event.getAsJsonObject("delta");
                if (delta == null) return "";
                if ("text_delta".equals(string(delta, "type"))) {
                    String value = string(delta, "text");
                    text.append(value);
                    return value;
                }
                if ("input_json_delta".equals(string(delta, "type")) && !calls.isEmpty()) {
                    calls.values().stream().reduce((first, ignored) -> ignored)
                            .ifPresent(call -> call.arguments.append(string(delta, "partial_json")));
                }
            }
            return "";
        }

        private String acceptResponses(JsonObject event) {
            String type = string(event, "type");
            if ("response.output_text.delta".equals(type)) {
                String value = string(event, "delta");
                text.append(value);
                return value;
            }
            if ("response.output_item.added".equals(type) || "response.output_item.done".equals(type)) {
                JsonObject item = event.getAsJsonObject("item");
                if (item != null && "function_call".equals(string(item, "type"))) {
                    String itemId = string(item, "id");
                    String callId = firstString(item, "call_id", "id");
                    MutableToolCall call = calls.computeIfAbsent(
                            callId,
                            ignored -> new MutableToolCall(
                                    callId, string(item, "name")
                            )
                    );
                    if (!itemId.isBlank()) {
                        callAliases.put(itemId, call);
                    }
                    if (item.has("arguments")) call.arguments.setLength(0);
                    if (item.has("arguments")) call.arguments.append(string(item, "arguments"));
                }
            }
            if ("response.function_call_arguments.delta".equals(type)) {
                String callId = firstString(event, "call_id", "item_id");
                MutableToolCall call = callAliases.get(callId);
                if (call == null) {
                    call = calls.computeIfAbsent(
                            callId, ignored -> new MutableToolCall(callId, string(event, "name"))
                    );
                }
                call.arguments.append(string(event, "delta"));
            }
            if ("response.completed".equals(type) && event.has("response")) {
                JsonObject response = event.getAsJsonObject("response");
                id = firstString(response, "id", "response_id");
                model = string(response, "model");
                JsonElement output = response.get("output");
                if (output != null && output.isJsonArray()) {
                    for (JsonElement element : output.getAsJsonArray()) {
                        if (element.isJsonObject() && "function_call".equals(string(element.getAsJsonObject(), "type"))) {
                            JsonObject item = element.getAsJsonObject();
                            String itemId = string(item, "id");
                            String callId = firstString(item, "call_id", "id");
                            MutableToolCall call = calls.computeIfAbsent(
                                    callId,
                                    ignored -> new MutableToolCall(callId, string(item, "name"))
                            );
                            if (!itemId.isBlank()) {
                                callAliases.put(itemId, call);
                            }
                            if (item.has("arguments")) {
                                call.arguments.setLength(0);
                                call.arguments.append(string(item, "arguments"));
                            }
                        }
                    }
                }
            }
            return "";
        }

        private String acceptGenerate(JsonObject event) {
            if (event.has("response") && event.get("response").isJsonObject()) {
                event = event.getAsJsonObject("response");
            }
            if (event.has("modelVersion")) model = string(event, "modelVersion");
            JsonArray candidates = event.has("candidates") && event.get("candidates").isJsonArray()
                    ? event.getAsJsonArray("candidates") : new JsonArray();
            if (candidates.isEmpty()) return "";
            JsonObject candidate = candidates.get(0).isJsonObject() ? candidates.get(0).getAsJsonObject() : null;
            if (candidate == null || !candidate.has("content")) return "";
            JsonObject content = candidate.getAsJsonObject("content");
            JsonArray parts = content.has("parts") && content.get("parts").isJsonArray()
                    ? content.getAsJsonArray("parts") : new JsonArray();
            StringBuilder delta = new StringBuilder();
            for (JsonElement element : parts) {
                if (!element.isJsonObject()) continue;
                JsonObject part = element.getAsJsonObject();
                String partText = string(part, "text");
                if (!partText.isBlank()) {
                    text.append(partText);
                    delta.append(partText);
                }
                if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                    JsonObject function = part.getAsJsonObject("functionCall");
                    String name = string(function, "name");
                    MutableToolCall call = calls.computeIfAbsent(name, ignored -> new MutableToolCall(name, name));
                    call.arguments.setLength(0);
                    call.arguments.append(jsonString(function.get("args")));
                }
            }
            return delta.toString();
        }

        private ChatResponse response() {
            List<ToolExecutionRequest> requests = calls.values().stream()
                    .map(call -> toolCall(call.id, call.name, call.arguments.toString()))
                    .toList();
            AiMessage message = requests.isEmpty() ? AiMessage.from(text.toString()) : AiMessage.from(text.toString(), requests);
            return ChatResponse.builder()
                    .aiMessage(message)
                    .id(id)
                    .modelName(model)
                    .finishReason(requests.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_EXECUTION)
                    .build();
        }

        private static final class MutableToolCall {
            private final String id;
            private final String name;
            private final StringBuilder arguments = new StringBuilder();

            private MutableToolCall(String id, String name) {
                this.id = id == null || id.isBlank() ? "call-" + System.nanoTime() : id;
                this.name = name == null ? "" : name;
            }
        }
    }
}
