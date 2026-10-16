package com.xhs.assistant.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LlmTypes {
    public record ImageData(String mediaType, String base64) {}
    public record Message(String role, String content, List<ImageData> images) {
        public static Message system(String text) { return new Message("system", text, List.of()); }
        public static Message user(String text) { return new Message("user", text, List.of()); }
        public static Message user(String text, List<ImageData> images) { return new Message("user", text, images); }
    }
    public record ToolSpec(String name, String description, Map<String, Object> parameters) {}
    public record ToolCall(String id, String name, String arguments) {}
    public record Usage(long inputTokens, long outputTokens, Long cacheReadTokens, Long cacheWriteTokens, String source) {}
    public record ChatRequest(String system, List<Message> messages, Double temperature, Integer maxOutputTokens, List<ToolSpec> tools) {}
    public interface StreamHandler {
        void text(String delta);
        default void usage(Usage usage) {}
    }
    public record ChatResult(String text, List<ToolCall> toolCalls, Usage usage, String finishReason) {
        public ChatResult(String text, Usage usage, String finishReason) { this(text, List.of(), usage, finishReason); }
    }
    public static class LlmException extends RuntimeException {
        public final int statusCode;
        public LlmException(String message, int statusCode, Throwable cause) { super(message, cause); this.statusCode = statusCode; }
        public LlmException(String message, int statusCode) { this(message, statusCode, null); }
    }
}
