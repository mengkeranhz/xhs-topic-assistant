package com.xhs.assistant.pipeline;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunContext {
    public final String taskId;
    public final long startedAt = System.currentTimeMillis();
    public final AtomicBoolean canceled = new AtomicBoolean(false);
    public volatile boolean timedOut;
    public final List<Map<String, Object>> stages = new ArrayList<>();
    public int retries;
    public int cacheHits;
    public int cacheMisses;
    public long queueWaitMs;
    public long searchDurationMs;
    public long detailDurationMs;
    public int captchaPauses;

    public RunContext(String taskId) { this.taskId = taskId; }

    public void checkCanceled() {
        if (canceled.get()) throw new CanceledException();
    }
    public static class CanceledException extends RuntimeException {}
}
