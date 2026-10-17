package com.xhs.assistant.api;

import com.xhs.assistant.domain.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.pipeline.*;
import com.xhs.assistant.storage.TaskRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.*;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskManager manager;
    private final TaskRepository repository;
    private final SseEventHub hub;
    private final AssistantProperties properties;
    private final ModelUsageStore modelUsageStore;
    private final IncrementalService incrementalService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public TaskController(TaskManager manager, TaskRepository repository, SseEventHub hub,
                          AssistantProperties properties, ModelUsageStore modelUsageStore,
                          IncrementalService incrementalService) {
        this.manager = manager; this.repository = repository; this.hub = hub;
        this.properties = properties; this.modelUsageStore = modelUsageStore;
        this.incrementalService = incrementalService;
    }

    @GetMapping("/browser/status")
    public Map<String, Object> browserStatus() {
        String url = properties.getBrowser().getCdpUrl().replaceAll("/$", "") + "/json/version";
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean connected = response.statusCode() / 100 == 2;
            return Map.of("connected", connected, "cdpUrl", properties.getBrowser().getCdpUrl(),
                    "message", connected ? "CDP 浏览器已连接" : "CDP 返回异常状态");
        } catch (Exception e) {
            return Map.of("connected", false, "cdpUrl", properties.getBrowser().getCdpUrl(),
                    "message", "未连接：请先运行 ./scripts/start-browser.sh");
        }
    }

    public record CreateRequest(@NotBlank String keyword, String runMode, String parentTaskId) {}
    public record RetryRequest(String stage) {}
    public record QuestionRequest(@NotBlank String question, String postRef) {}

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest request) {
        Task task = manager.create(request.keyword(), request.runMode(), request.parentTaskId());
        return envelope(task);
    }

    @GetMapping("/tasks")
    public List<Task> list() { return manager.list(); }

    @GetMapping("/tasks/{taskId}")
    public Task status(@PathVariable String taskId) { return manager.get(taskId); }

    @GetMapping("/tasks/{taskId}/stream")
    public SseEmitter stream(@PathVariable String taskId) {
        Task task = manager.get(taskId);
        SseEmitter emitter = new SseEmitter(0L);
        hub.subscribe(taskId, emitter);
        List<TaskEvent> old = manager.events(taskId);
        for (TaskEvent event : old.subList(Math.max(0, old.size() - 100), old.size())) {
            try { emitter.send(SseEmitter.event().name(event.type).data(event)); } catch (Exception ignored) {}
        }
        try { emitter.send(SseEmitter.event().name("status").data(task)); } catch (Exception ignored) {}
        return emitter;
    }

    @GetMapping("/tasks/{taskId}/result")
    public ResponseEntity<Map<String, Object>> result(@PathVariable String taskId) {
        Task task = manager.get(taskId);
        if (!task.status.isTerminal()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", task.status.name(),
                    "stage", task.orchestration.currentStage, "message", "TASK_NOT_FINISHED"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("report", repository.report(taskId).orElse(null));
        out.put("posts", repository.posts(taskId));
        out.put("diff", repository.diff(taskId).orElse(null));
        out.put("metrics", repository.metrics(taskId).orElse(null));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/tasks/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable String taskId, @RequestBody(required = false) RetryRequest request) {
        return envelope(manager.retry(taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> cancel(@PathVariable String taskId) { return envelope(manager.cancel(taskId)); }

    @PostMapping("/tasks/{taskId}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> rerun(@PathVariable String taskId) { return envelope(manager.rerun(taskId)); }

    @GetMapping("/tasks/{taskId}/diff")
    public ResponseEntity<DiffRecord> diff(@PathVariable String taskId) {
        return ResponseEntity.of(repository.diff(taskId));
    }

    @PostMapping("/tasks/{taskId}/diff/recompute")
    public DiffRecord recomputeDiff(@PathVariable String taskId) {
        Task task = manager.get(taskId);
        if (task.parentTaskId == null || task.parentTaskId.isBlank()) {
            throw new IllegalArgumentException("INCREMENTAL_PARENT_REQUIRED");
        }
        return incrementalService.compare(taskId, task.parentTaskId);
    }

    @PostMapping("/tasks/{taskId}/posts/{postRef}/5w1h")
    public ResponseEntity<AnalysisStatus> createFive(@PathVariable String taskId, @PathVariable String postRef) {
        AnalysisStatus status = manager.startFiveW1H(taskId, postRef);
        return ResponseEntity.status("COMPLETED".equals(status.status) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/tasks/{taskId}/posts/{postRef}/5w1h")
    public ResponseEntity<Map<String, Object>> getFive(@PathVariable String taskId, @PathVariable String postRef) {
        AnalysisStatus status = manager.analysisStatus(taskId, postRef);
        if (status.result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status.result);
    }

    @GetMapping("/tasks/{taskId}/posts/{postRef}/5w1h/status")
    public AnalysisStatus fiveStatus(@PathVariable String taskId, @PathVariable String postRef) {
        return manager.analysisStatus(taskId, postRef);
    }

    @PostMapping("/tasks/{taskId}/questions")
    public ResponseEntity<QuestionRecord> ask(@PathVariable String taskId, @Valid @RequestBody QuestionRequest request) {
        QuestionRecord q = manager.startQuestion(taskId, request.question(), request.postRef());
        return ResponseEntity.status(q.status.equals("COMPLETED") ? HttpStatus.OK : HttpStatus.ACCEPTED).body(q);
    }

    @GetMapping("/tasks/{taskId}/questions")
    public List<QuestionRecord> questions(@PathVariable String taskId) { return repository.questions(taskId); }

    @GetMapping("/tasks/{taskId}/questions/{questionId}")
    public QuestionRecord question(@PathVariable String taskId, @PathVariable String questionId) {
        return repository.question(taskId, questionId).orElseThrow();
    }

    @GetMapping("/tasks/{taskId}/model-usage")
    public List<ModelUsageRecord> modelUsage(@PathVariable String taskId) {
        return modelUsageStore.list(taskId);
    }

    @GetMapping("/tasks/{taskId}/metrics")
    public MetricsRecord metrics(@PathVariable String taskId) {
        return repository.metrics(taskId).orElseGet(() -> new MetricsRecord());
    }

    private Map<String, Object> envelope(Task task) {
        return new LinkedHashMap<>(Map.of(
                "taskId", task.taskId, "status", task.status.name(), "acceptedAt", String.valueOf(task.createdAt),
                "statusUrl", "/api/tasks/" + task.taskId,
                "streamUrl", "/api/tasks/" + task.taskId + "/stream",
                "resultUrl", "/api/tasks/" + task.taskId + "/result",
                "pollIntervalMs", 2000
        ));
    }
}
