package com.xhs.assistant.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhs.assistant.domain.ModelUsageRecord;
import com.xhs.assistant.llm.LlmTypes;
import com.xhs.assistant.storage.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ModelUsageStore {
    private static final Logger log = LoggerFactory.getLogger(ModelUsageStore.class);
    private final TaskRepository repository;
    private final ObjectMapper mapper;

    public ModelUsageStore(TaskRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper.copy();
        this.mapper.findAndRegisterModules();
    }

    public void record(String taskId, String action, LlmTypes.Usage usage) {
        if (usage == null) return;
        try {
            ModelUsageRecord row = new ModelUsageRecord();
            row.taskId = taskId; row.action = action;
            row.inputTokens = usage.inputTokens(); row.outputTokens = usage.outputTokens();
            row.createdAt = OffsetDateTime.now();
            repository.appendModelUsage(taskId, row);
        } catch (Exception e) {
            log.warn("Cannot persist model usage for task {}", taskId, e);
        }
    }

    public List<ModelUsageRecord> list(String taskId) {
        var file = repository.taskDir(taskId).resolve("model-usage.md");
        if (!Files.exists(file)) return List.of();
        try {
            List<ModelUsageRecord> rows = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.startsWith("- ")) continue;
                rows.add(mapper.readValue(line.substring(2), ModelUsageRecord.class));
            }
            return rows;
        } catch (Exception e) {
            log.warn("Cannot read model usage for task {}", taskId, e);
            return List.of();
        }
    }
}
