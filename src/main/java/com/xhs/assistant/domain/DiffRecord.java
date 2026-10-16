package com.xhs.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DiffRecord {
    public String taskId;
    public String baseTaskId;
    public List<String> newPosts = new ArrayList<>();
    public List<String> missingPosts = new ArrayList<>();
    public List<Map<String, Object>> metricChanges = new ArrayList<>();
    public List<String> newOpinions = new ArrayList<>();
    public Map<String, Object> reused = Map.of();
}
