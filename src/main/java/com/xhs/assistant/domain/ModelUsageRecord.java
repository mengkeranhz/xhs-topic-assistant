package com.xhs.assistant.domain;

import java.time.OffsetDateTime;

public class ModelUsageRecord {
    public String taskId;
    public String action;
    public long calls = 1;
    public long inputTokens;
    public long outputTokens;
    public String usageSource = "API_RESPONSE";
    public OffsetDateTime createdAt;
}
