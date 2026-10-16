package com.xhs.assistant.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Task {
    public String taskId;
    public String parentTaskId;
    public String keyword;
    public String normalizedKeyword;
    public String runMode = "NEW";
    public TaskStatus status = TaskStatus.CREATED;
    public OffsetDateTime createdAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public long timeLimitSeconds = 900;
    public Orchestration orchestration = new Orchestration();
    public WorkerInfo worker = new WorkerInfo();
    public CollectionSummary collection = new CollectionSummary();
    public Map<String, Object> ranking = Map.of();
    public String failureCode;
    public String failureMessage;
    public String waitingReason;

    public static class Orchestration {
        public String currentStage = "CREATED";
        public int stageAttempt = 1;
        public boolean autoAdvanced = true;
        public String lastCompletedStage = "";
        public Checkpoint checkpoint = new Checkpoint();
    }

    public static class Checkpoint {
        public boolean candidatePoolSaved;
        public boolean selectedPostsSaved;
        public boolean reportSaved;
        public List<String> completedDetailPostRefs = new ArrayList<>();
        public List<String> failedDetailPostRefs = new ArrayList<>();
    }

    public static class WorkerInfo {
        public String workerId;
        public OffsetDateTime heartbeatAt;
        public OffsetDateTime leaseExpiresAt;
        public long lastEventSeq;
    }

    public static class CollectionSummary {
        public OffsetDateTime startedAt;
        public OffsetDateTime finishedAt;
        public int candidateCount;
        public int distinctPostCount;
        public int relevantCount;
        public int selectedCount;
        public int successfulDetailCount;
        public int failedDetailCount;
    }
}
