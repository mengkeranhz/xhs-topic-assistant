package com.xhs.assistant.pipeline;

import com.xhs.assistant.domain.Task;
import com.xhs.assistant.domain.TaskEvent;
import com.xhs.assistant.storage.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class TaskEventPublisher {
    private final TaskRepository repository;
    private final SseEventHub hub;

    public TaskEventPublisher(TaskRepository repository, SseEventHub hub) {
        this.repository = repository; this.hub = hub;
    }

    public void publish(Task task, String type, String message, Object data) {
        long seq = task.worker.lastEventSeq + 1;
        TaskEvent event = new TaskEvent(seq, type, message, data, OffsetDateTime.now());
        task.worker.lastEventSeq = seq;
        task.worker.heartbeatAt = event.timestamp;
        repository.saveTask(task);
        repository.appendEvent(task.taskId, event);
        hub.send(task.taskId, event);
    }

    public void status(Task task) { publish(task, "status", task.orchestration.currentStage, Map.of("status", task.status.name(), "stage", task.orchestration.currentStage)); }
    public void log(Task task, String message) { publish(task, "log", message, Map.of()); }
    public void agentStatus(Task task, int round, String tool, String status, boolean cacheHit) {
        publish(task, "agent-status", tool + " " + status, Map.of("round", round, "tool", tool, "status", status, "cacheHit", cacheHit));
    }
    public void reportToken(Task task, String text) { hub.send(task.taskId, new TaskEvent(task.worker.lastEventSeq, "report-token", text, text, OffsetDateTime.now())); }
}
