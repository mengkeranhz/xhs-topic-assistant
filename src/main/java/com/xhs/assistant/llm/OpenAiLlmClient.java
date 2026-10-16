package com.xhs.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.llm.LlmTypes.*;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component("openAiLlmClient")
public class OpenAiLlmClient implements LlmClient {
    private final HttpClient http;
    private final AssistantProperties properties;
    private final ObjectMapper mapper;

    public OpenAiLlmClient(AssistantProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public ChatResult chat(ChatRequest request, boolean vision) {
        HttpResponse<String> response = exchange(request, vision, false, null,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new LlmException("OpenAI API failed: " + response.body(), response.statusCode());
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String text = message.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : message.path("tool_calls")) {
                calls.add(new ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")));
            }
            return new ChatResult(text, calls, usage(root.path("usage")), choice.path("finish_reason").asText("stop"));
        } catch (Exception e) { throw new LlmException("Cannot parse OpenAI response", 0, e); }
    }

    @Override
    public void stream(ChatRequest request, StreamHandler handler) {
        HttpResponse<InputStream> response = exchange(request, false, true, handler,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new LlmException("OpenAI stream failed", response.statusCode());
    }

    private <T> HttpResponse<T> exchange(ChatRequest request, boolean vision, boolean stream, StreamHandler handler,
                                             HttpResponse.BodyHandler<T> responseHandler) {
        try {
            var cfg = properties.getModel().getOpenai();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", vision ? cfg.getVisionModel() : cfg.getChatModel());
            body.put("temperature", request.temperature() != null ? request.temperature() : properties.getModel().getTemperature());
            if (request.maxOutputTokens() != null) body.put("max_tokens", request.maxOutputTokens());
            body.put("stream", (boolean) stream);
            if (stream) body.putObject("stream_options").put("include_usage", true);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", request.system());
            for (Message m : request.messages()) {
                if (m.images() == null || m.images().isEmpty()) {
                    messages.addObject().put("role", m.role()).put("content", m.content());
                } else {
                    ObjectNode mm = messages.addObject(); mm.put("role", m.role());
                    ArrayNode parts = mm.putArray("content");
                    parts.addObject().put("type", "text").put("text", m.content());
                    for (ImageData img : m.images()) {
                        ObjectNode p = parts.addObject(); p.put("type", "image_url");
                        p.putObject("image_url").put("url", "data:" + img.mediaType() + ";base64," + img.base64());
                    }
                }
            }
            if (request.tools() != null && !request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (ToolSpec spec : request.tools()) {
                    ObjectNode t = tools.addObject(); t.put("type", "function");
                    ObjectNode fn = t.putObject("function"); fn.put("name", spec.name()); fn.put("description", spec.description());
                    fn.set("parameters", mapper.valueToTree(spec.parameters()));
                }
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(stripSlash(cfg.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + apiKey(cfg.getApiKey(), "OPENAI_API_KEY"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<T> response = http.send(rb.build(), responseHandler);
            if (stream && handler != null && response.statusCode() / 100 == 2) {
                try (InputStream bodyStream = (InputStream) response.body();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) break;
                        JsonNode root = mapper.readTree(data);
                        String delta = root.path("choices").path(0).path("delta").path("content").asText(null);
                        if (delta != null && !delta.isEmpty()) handler.text(delta);
                        if (!root.path("usage").isMissingNode()) handler.usage(usage(root.path("usage")));
                    }
                }
            }
            return response;
        } catch (LlmException e) { throw e; }
        catch (Exception e) { throw new LlmException("OpenAI request failed", 0, e); }
    }

    private Usage usage(JsonNode node) {
        return new Usage(node.path("prompt_tokens").asLong(0), node.path("completion_tokens").asLong(0),
                node.hasNonNull("prompt_tokens_details") ? node.path("prompt_tokens_details").path("cached_tokens").asLong(0) : null,
                null, "API_RESPONSE");
    }

    static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    static String apiKey(String value, String env) {
        if (value != null && !value.isBlank() && !value.startsWith("${")) return value;
        String v = System.getenv(env);
        if (v == null || v.isBlank()) throw new LlmException("Missing API key: " + env, 401);
        return v;
    }
}
