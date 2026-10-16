package com.xhs.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.llm.LlmTypes.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component("anthropicLlmClient")
public class AnthropicLlmClient implements LlmClient {
    private final HttpClient http;
    private final AssistantProperties properties;
    private final ObjectMapper mapper;

    public AnthropicLlmClient(AssistantProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public ChatResult chat(ChatRequest request, boolean vision) {
        HttpResponse<String> response = exchange(request, vision, false, null,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new LlmException("Anthropic API failed: " + response.body(), response.statusCode());
        try {
            JsonNode root = mapper.readTree(response.body());
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(),
                            block.path("input").toString()));
                }
            }
            return new ChatResult(text.toString(), calls, usage(root.path("usage")), root.path("stop_reason").asText("end_turn"));
        } catch (Exception e) { throw new LlmException("Cannot parse Anthropic response", 0, e); }
    }

    @Override
    public void stream(ChatRequest request, StreamHandler handler) {
        HttpResponse<InputStream> response = exchange(request, false, true, handler,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new LlmException("Anthropic stream failed", response.statusCode());
    }

    private <T> HttpResponse<T> exchange(ChatRequest request, boolean vision, boolean stream, StreamHandler handler,
                                             HttpResponse.BodyHandler<T> responseHandler) {
        try {
            var cfg = properties.getModel().getAnthropic();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", vision ? cfg.getVisionModel() : cfg.getChatModel());
            body.put("system", request.system());
            body.put("max_tokens", request.maxOutputTokens() == null ? 4096 : request.maxOutputTokens());
            body.put("temperature", request.temperature() != null ? request.temperature() : properties.getModel().getTemperature());
            body.put("stream", (boolean) stream);
            ArrayNode messages = body.putArray("messages");
            for (Message m : request.messages()) {
                ObjectNode mm = messages.addObject(); mm.put("role", m.role().equals("assistant") ? "assistant" : "user");
                ArrayNode content = mm.putArray("content");
                if (m.content() != null && !m.content().isBlank()) content.addObject().put("type", "text").put("text", m.content());
                for (ImageData img : m.images()) {
                    ObjectNode source = content.addObject().put("type", "image").putObject("source");
                    source.put("type", "base64").put("media_type", img.mediaType()).put("data", img.base64());
                }
            }
            if (request.tools() != null) {
                for (ToolSpec spec : request.tools()) {
                    ObjectNode t = body.putArray("tools").addObject();
                    t.put("name", spec.name()); t.put("description", spec.description());
                    t.set("input_schema", mapper.valueToTree(spec.parameters()));
                }
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(anthropicMessagesUrl(cfg.getBaseUrl())))
                    .timeout(Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
                    .header("x-api-key", OpenAiLlmClient.apiKey(cfg.getApiKey(), "ANTHROPIC_API_KEY"))
                    .header("anthropic-version", cfg.getApiVersion())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<T> response = http.send(rb.build(), responseHandler);
            if (stream && handler != null && response.statusCode() / 100 == 2) {
                try (InputStream bodyStream = (InputStream) response.body();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        JsonNode root = mapper.readTree(line.substring(5).trim());
                        String type = root.path("type").asText();
                        if ("content_block_delta".equals(type) && "text_delta".equals(root.path("delta").path("type").asText()))
                            handler.text(root.path("delta").path("text").asText());
                        if ("message_start".equals(type) || ("message_delta".equals(type) && root.has("usage"))) handler.usage(usage(root.path("message").path("usage").isMissingNode() ? root.path("usage") : root.path("message").path("usage")));
                    }
                }
            }
            return response;
        } catch (LlmException e) { throw e; }
        catch (Exception e) { throw new LlmException("Anthropic request failed", 0, e); }
    }

    private static String anthropicMessagesUrl(String baseUrl) {
        String base = OpenAiLlmClient.stripSlash(baseUrl);
        return (base.endsWith("/v1") ? base : base + "/v1") + "/messages";
    }

    private Usage usage(JsonNode node) {
        return new Usage(node.path("input_tokens").asLong(0), node.path("output_tokens").asLong(0),
                node.hasNonNull("cache_read_input_tokens") ? node.path("cache_read_input_tokens").asLong(0) : null,
                node.hasNonNull("cache_creation_input_tokens") ? node.path("cache_creation_input_tokens").asLong(0) : null,
                "API_RESPONSE");
    }
}
