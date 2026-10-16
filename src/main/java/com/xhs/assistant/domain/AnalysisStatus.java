package com.xhs.assistant.domain;

import java.time.OffsetDateTime;
import java.util.Map;

public class AnalysisStatus {
    public String taskId;
    public String postRef;
    public String status;
    public Map<String, Object> result;
    public String failure;
    public OffsetDateTime updatedAt;
}
