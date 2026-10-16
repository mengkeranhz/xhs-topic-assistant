package com.xhs.assistant.llm;

import java.util.List;
import com.xhs.assistant.llm.LlmTypes.*;

public interface LlmClient {
    ChatResult chat(ChatRequest request, boolean vision);
    void stream(ChatRequest request, StreamHandler handler);
    default ChatResult extractImageText(String instruction, List<ImageData> images) {
        return chat(new ChatRequest("You extract visible text from images. Return only a JSON object {\"text\":string,\"confidence\":string,\"notes\":string[]}.",
                List.of(Message.user(instruction, images)), 0.0, 1200, List.of()), true);
    }
}
