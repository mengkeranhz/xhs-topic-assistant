package com.xhs.assistant.pipeline;

import com.xhs.assistant.collector.LoginRequiredException;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.TaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Component
public class TaskManager {
    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private final TaskRepository repository;
    private final TaskOrchestrator orchestrator;
    private final TaskEventPublisher events;
    private final ModelService modelService;
    private final AssistantProperties properties;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, AnalysisStatus> analyses = new ConcurrentHashMap<>();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentTaskId = new AtomicReference<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService sideJobs = Executors.newVirtualThreadPerTaskExecutor();

    public TaskManager(TaskRepository repository, TaskOrchestrator orchestrator, TaskEventPublisher events,
                       ModelService modelService, AssistantProperties properties) {
        this.repository = repository; this.orchestrator = orchestrator; this.events = events;
        this.modelService = modelService; this.properties = properties;
    }

    @PostConstruct
    public void init() {
        for (Task task : repository.loadAll()) {
            tasks.put(task.taskId, task);
            if (task.status.isRunnable()) {
                task.status = TaskStatus.INTERRUPTED;
                task.failureCode = "SERVICE_RESTARTED";
                task.failureMessage = "服务重启，任务已中断";
                repository.saveTask(task);
                enqueue(task, false);
            }
        }
        worker.submit(this::workerLoop);
        monitor.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        monitor.shutdownNow();
        worker.shutdownNow();
        sideJobs.shutdownNow();
    }

    public synchronized Task create(String keyword, String runMode, String parentTaskId) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) throw new IllegalArgumentException("keyword cannot be blank");
        for (Task t : tasks.values()) {
            if (normalized.equals(t.normalizedKeyword) && t.status.isRunnable()) return t;
        }
        Task task = new Task();
        task.taskId = OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                "-" + slug(keyword) + "-" + UUID.randomUUID().toString().substring(0, 6);
        task.keyword = keyword.trim();
        task.normalizedKeyword = normalized;
        task.runMode = "INCREMENTAL".equalsIgnoreCase(runMode) ? "INCREMENTAL" : "NEW";
        task.parentTaskId = parentTaskId;
        task.status = TaskStatus.QUEUED;
        task.createdAt = OffsetDateTime.now();
        task.timeLimitSeconds = properties.getBrowser().getTaskTimeoutSeconds();
        task.orchestration.currentStage = "QUEUED";
        task.orchestration.autoAdvanced = true;
        repository.saveTask(task);
        tasks.put(task.taskId, task);
        enqueue(task, true);
        return task;
    }

    public synchronized Task rerun(String parentTaskId) {
        Task parent = get(parentTaskId);
        if (parent.status.isTerminal()) return create(parent.keyword, "INCREMENTAL", parent.taskId);
        throw new IllegalStateException("PARENT_TASK_NOT_FINISHED");
    }

    public synchronized Task retry(String taskId) {
        Task task = get(taskId);
        boolean alreadyExecuting = contexts.containsKey(taskId) && task.status.isRunnable()
                && task.status != TaskStatus.WAITING_USER && task.status != TaskStatus.INTERRUPTED;
        if (alreadyExecuting) return task;
        task.status = TaskStatus.QUEUED;
        task.failureCode = null; task.failureMessage = null; task.waitingReason = null;
        task.orchestration.currentStage = "RETRY_FROM_CHECKPOINT";
        repository.saveTask(task);
        enqueue(task, true);
        return task;
    }

    public synchronized Task cancel(String taskId) {
        Task task = get(taskId);
        if (!task.status.isTerminal()) {
            RunContext ctx = contexts.get(taskId);
            if (ctx != null) ctx.canceled.set(true);
            task.status = TaskStatus.CANCELED;
            task.finishedAt = OffsetDateTime.now();
            repository.saveTask(task);
            events.publish(task, "done", "CANCELED", Map.of("status", "CANCELED"));
        }
        return task;
    }

    public List<Task> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparing((Task t) -> Optional.ofNullable(t.createdAt).orElse(OffsetDateTime.MIN)).reversed())
                .toList();
    }

    public Task get(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null) return task;
        Task loaded = repository.find(taskId).orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));
        tasks.put(taskId, loaded);
        return loaded;
    }

    public List<TaskEvent> events(String taskId) { return repository.events(taskId); }

    private void enqueue(Task task, boolean publish) {
        RunContext context = new RunContext(task.taskId);
        contexts.put(task.taskId, context);
        queue.add(task.taskId);
        if (publish) events.publish(task, "status", "任务已入队", Map.of("status", "QUEUED", "stage", "QUEUED"));
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String taskId = queue.poll(1, TimeUnit.SECONDS);
                if (taskId == null) continue;
                Task task = tasks.get(taskId);
                RunContext context = contexts.get(taskId);
                if (task == null || context == null || !task.status.isRunnable()) continue;
                context.queueWaitMs = System.currentTimeMillis() - Optional.ofNullable(task.createdAt).orElse(OffsetDateTime.now()).toInstant().toEpochMilli();
                currentTaskId.set(taskId);
                task.worker.workerId = "local-worker-1";
                task.worker.leaseExpiresAt = OffsetDateTime.now().plusSeconds(30);
                repository.saveTask(task);
                try {
                    orchestrator.run(task, context);
                } catch (LoginRequiredException e) {
                    task.status = TaskStatus.WAITING_USER;
                    task.waitingReason = e.getMessage();
                    task.failureCode = "WAITING_USER";
                    task.failureMessage = e.getMessage();
                    repository.saveTask(task);
                    events.publish(task, "waiting-user", e.getMessage(), Map.of("reason", e.getMessage()));
                } catch (RunContext.CanceledException e) {
                    boolean hasReport = repository.report(taskId).isPresent();
                    task.status = context.timedOut && hasReport ? TaskStatus.PARTIALLY_COMPLETED : TaskStatus.CANCELED;
                    if (context.timedOut) { task.failureCode = "TASK_TIMEOUT"; task.failureMessage = "任务超过时间上限，已保留已完成部分"; }
                    repository.saveTask(task);
                    events.publish(task, "done", task.status.name(), Map.of("status", task.status.name(), "timedOut", context.timedOut));
                } catch (Exception e) {
                    String message = String.valueOf(e.getMessage());
                    if (message.contains("ECONNREFUSED") || message.contains("9222")) {
                        task.status = TaskStatus.WAITING_USER;
                        task.waitingReason = "CDP_BROWSER_NOT_RUNNING";
                        task.failureCode = "CDP_BROWSER_NOT_RUNNING";
                        task.failureMessage = "请先运行 ./scripts/start-browser.sh 并保持浏览器开启";
                        repository.saveTask(task);
                        events.publish(task, "waiting-user", task.failureMessage,
                                Map.of("reason", "CDP_BROWSER_NOT_RUNNING"));
                        continue;
                    }
                    log.error("Task {} failed", taskId, e);
                    boolean hasReport = repository.report(taskId).isPresent();
                    task.status = hasReport ? TaskStatus.PARTIALLY_COMPLETED : TaskStatus.FAILED;
                    task.failureCode = e.getClass().getSimpleName();
                    task.failureMessage = String.valueOf(e.getMessage());
                    task.finishedAt = OffsetDateTime.now();
                    try { orchestrator.saveMetrics(task, context); } catch (Exception ignored) {}
                    repository.saveTask(task);
                    events.publish(task, "error", task.failureMessage, Map.of("code", task.failureCode, "status", task.status.name()));
                } finally {
                    contexts.remove(taskId);
                    currentTaskId.compareAndSet(taskId, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Worker loop error", e);
            }
        }
    }

    private void heartbeat() {
        String id = currentTaskId.get();
        if (id == null) return;
        Task task = tasks.get(id);
        if (task == null || !task.status.isRunnable()) return;
        OffsetDateTime now = OffsetDateTime.now();
        long elapsed = task.startedAt == null ? 0 : java.time.Duration.between(task.startedAt, now).toSeconds();
        if (elapsed > task.timeLimitSeconds) {
            RunContext context = contexts.get(id);
            if (context != null) { context.timedOut = true; context.canceled.set(true); }
            task.failureCode = "TASK_TIMEOUT";
            task.failureMessage = "任务超过时间上限";
            events.publish(task, "error", "任务超过时间上限", Map.of("code", "TASK_TIMEOUT"));
            return;
        }
        task.worker.workerId = "local-worker-1";
        task.worker.heartbeatAt = now;
        task.worker.leaseExpiresAt = now.plusSeconds(30);
        repository.saveTask(task);
    }

    public AnalysisStatus startFiveW1H(String taskId, String postRef) {
        Task task = get(taskId);
        Post post = repository.post(taskId, postRef).orElseThrow(() -> new NoSuchElementException("Post not found"));
        if (post.fiveW1H != null) return completedAnalysis(taskId, postRef, post.fiveW1H);
        AnalysisStatus status = analyses.computeIfAbsent(taskId + ":" + postRef, k -> {
            AnalysisStatus s = new AnalysisStatus();
            s.taskId = taskId; s.postRef = postRef; s.status = "NOT_STARTED"; s.updatedAt = OffsetDateTime.now();
            return s;
        });
        if ("COMPLETED".equals(status.status)) return status;
        if ("RUNNING".equals(status.status)) return status;
        status.status = "RUNNING";
        status.failure = null;
        status.updatedAt = OffsetDateTime.now();
        sideJobs.submit(() -> {
            try {
                Map<String, Object> evidence = postEvidence(post);
                Map<String, Object> result = modelService.generateFiveW1H(task.taskId, task.keyword, evidence);
                Post latest = repository.post(taskId, postRef).orElse(post);
                latest.fiveW1H = result;
                repository.savePost(taskId, latest);
                repository.saveCachePost(latest);
                status.status = "COMPLETED"; status.result = result; status.updatedAt = OffsetDateTime.now();
                events.publish(task, "log", "5W1H 完成：" + postRef, Map.of("postRef", postRef));
            } catch (Exception e) {
                status.status = "FAILED"; status.failure = e.getMessage(); status.updatedAt = OffsetDateTime.now();
                events.publish(task, "error", "5W1H 失败：" + postRef, Map.of("postRef", postRef, "reason", e.getMessage()));
            }
        });
        return status;
    }

    public AnalysisStatus analysisStatus(String taskId, String postRef) {
        Post post = repository.post(taskId, postRef).orElse(null);
        if (post != null && post.fiveW1H != null) return completedAnalysis(taskId, postRef, post.fiveW1H);
        return analyses.getOrDefault(taskId + ":" + postRef, pendingAnalysis(taskId, postRef));
    }

    private AnalysisStatus completedAnalysis(String taskId, String postRef, Map<String, Object> result) {
        AnalysisStatus s = new AnalysisStatus(); s.taskId = taskId; s.postRef = postRef;
        s.status = "COMPLETED"; s.result = result; s.updatedAt = OffsetDateTime.now(); return s;
    }

    private AnalysisStatus pendingAnalysis(String taskId, String postRef) {
        AnalysisStatus s = new AnalysisStatus(); s.taskId = taskId; s.postRef = postRef;
        s.status = "NOT_STARTED"; s.updatedAt = OffsetDateTime.now(); return s;
    }

    public QuestionRecord startQuestion(String taskId, String question, String postRef) {
        Task task = get(taskId);
        if (!task.status.isTerminal()) throw new IllegalArgumentException("MATERIAL_NOT_READY");
        QuestionRecord q = new QuestionRecord();
        q.questionId = "Q" + String.format("%04d", repository.questions(taskId).size() + 1);
        q.taskId = taskId; q.postRef = postRef; q.question = question;
        q.status = "QUEUED"; q.createdAt = OffsetDateTime.now();
        repository.saveQuestion(q);
        sideJobs.submit(() -> {
            q.status = "RUNNING";
            repository.saveQuestion(q);
            try {
                List<Map<String, Object>> evidence = new ArrayList<>();
                for (Post p : repository.posts(taskId)) {
                    if (postRef == null || postRef.isBlank() || postRef.equals(p.postRef)) evidence.add(postEvidence(p));
                }
                modelService.answerQuestion(taskId, q, evidence);
                q.finishedAt = OffsetDateTime.now();
                repository.saveQuestion(q);
                events.publish(task, "log", "追问完成：" + q.questionId, Map.of("questionId", q.questionId));
            } catch (Exception e) {
                q.status = "FAILED"; q.failure = e.getMessage(); q.finishedAt = OffsetDateTime.now();
                repository.saveQuestion(q);
                events.publish(task, "error", "追问失败：" + q.questionId, Map.of("questionId", q.questionId, "reason", e.getMessage()));
            }
        });
        return q;
    }

    private Map<String, Object> postEvidence(Post p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postRef", p.postRef); m.put("postId", p.postId); m.put("url", p.url);
        m.put("title", p.title); m.put("author", p.author); m.put("publishedAt", p.publishedAt);
        m.put("body", p.body); m.put("imageText", p.imageText.stream().map(x -> x.text).toList());
        m.put("coverage", p.coverage); m.put("coverageNote", p.coverageNote);
        m.put("likesRaw", p.likes.raw); m.put("likesPrecision", p.likes.precision);
        return m;
    }

    private String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-").replaceAll("(^-|-$)", "");
    }
}
