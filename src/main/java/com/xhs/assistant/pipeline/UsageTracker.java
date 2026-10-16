package com.xhs.assistant.pipeline;

import com.xhs.assistant.llm.LlmTypes;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UsageTracker {
    public static final class Counters {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong inputTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong agentRounds = new AtomicLong();
        volatile String finalAnswerGate = "NOT_RUN";
    }
    private final Map<String, Counters> byTask = new ConcurrentHashMap<>();

    public Counters get(String taskId) { return byTask.computeIfAbsent(taskId, k -> new Counters()); }
    public void usage(String taskId, LlmTypes.Usage usage) {
        if (usage == null) return;
        Counters c = get(taskId);
        c.calls.incrementAndGet();
        c.inputTokens.addAndGet(usage.inputTokens());
        c.outputTokens.addAndGet(usage.outputTokens());
    }
    public void clear(String taskId) { byTask.remove(taskId); }
}
