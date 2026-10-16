package com.xhs.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MetricsRecord {
    public String taskId;
    public long totalDurationMs;
    public Map<String, Object> queue = Map.of();
    public Map<String, Object> collector = Map.of();
    public Map<String, Object> model = Map.of();
    public Map<String, Object> cumulativeModel = Map.of();
    public List<Map<String, Object>> runs = new ArrayList<>();
    public List<Map<String, Object>> stages = new ArrayList<>();
}
