package com.xhs.assistant.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReportRecord {
    public String taskId;
    public String keyword;
    public OffsetDateTime generatedAt;
    public String scope;
    public List<String> analyzedPostRefs = new ArrayList<>();
    public Map<String, Object> citationValidation = Map.of();
    public Map<String, Object> modelUsage = Map.of();
    public String reportMarkdown = "";
    public List<Map<String, Object>> agentTrace = new ArrayList<>();
    public String finalAnswerGate = "NOT_RUN";
    public List<String> failedChecks = new ArrayList<>();
}
