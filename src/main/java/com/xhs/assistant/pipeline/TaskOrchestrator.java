package com.xhs.assistant.pipeline;

import com.xhs.assistant.collector.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.*;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.Comparator;

@Component
public class TaskOrchestrator {
    private final TaskRepository repository;
    private final MarkdownStore markdownStore;
    private final XhsCollector collector;
    private final ModelService modelService;
    private final ReportAgent reportAgent;
    private final IncrementalService incrementalService;
    private final TaskEventPublisher events;
    private final UsageTracker usageTracker;
    private final AssistantProperties properties;

    public TaskOrchestrator(TaskRepository repository, MarkdownStore markdownStore, XhsCollector collector,
                            ModelService modelService, ReportAgent reportAgent, IncrementalService incrementalService,
                            TaskEventPublisher events, UsageTracker usageTracker, AssistantProperties properties) {
        this.repository = repository; this.markdownStore = markdownStore; this.collector = collector;
        this.modelService = modelService; this.reportAgent = reportAgent; this.incrementalService = incrementalService;
        this.events = events; this.usageTracker = usageTracker; this.properties = properties;
    }

    public void run(Task task, RunContext context) {
        task.startedAt = OffsetDateTime.now();
        task.collection.startedAt = task.startedAt;
        setStage(task, context, "QUEUED", TaskStatus.WAITING_BROWSER, 0.02);

        long t = System.currentTimeMillis();
        List<Post> candidates = collector.collectCandidates(task.keyword);
        context.searchDurationMs = System.currentTimeMillis() - t;
        context.checkCanceled();
        task.collection.candidateCount = candidates.size();
        task.collection.distinctPostCount = candidates.size();
        task.orchestration.checkpoint.candidatePoolSaved = true;
        repository.saveTask(task);
        saveCandidates(task, candidates);
        events.log(task, "外层搜索完成：收集到 " + candidates.size() + " 个不同候选帖子");

        setStage(task, context, "SEARCHING", TaskStatus.CLASSIFYING, 0.20);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Post p : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("postId", p.postId); m.put("title", p.title);
            m.put("summary", p.author + " " + p.publishedAt); m.put("url", p.url);
            summaries.add(m);
        }
        Map<String, Map<String, Object>> classified = new HashMap<>();
        for (Map<String, Object> x : modelService.classifyRelevance(task.taskId, task.keyword, summaries)) {
            classified.put(String.valueOf(x.get("postId")), x);
        }
        for (Post p : candidates) {
            Map<String, Object> x = classified.getOrDefault(p.postId, Map.of("label", "MAYBE", "score", 0.5, "reason", "not classified"));
            p.relevance = String.valueOf(x.getOrDefault("label", "MAYBE"));
            p.relevanceScore = x.get("score") instanceof Number n ? n.doubleValue() : 0.5;
            p.relevanceReason = String.valueOf(x.getOrDefault("reason", ""));
        }
        List<Post> relevant = candidates.stream()
                .filter(p -> !"IRRELEVANT".equalsIgnoreCase(p.relevance))
                .sorted(this::ranking)
                .limit(properties.getBrowser().getDetailLimit())
                .toList();
        for (int i = 0; i < relevant.size(); i++) relevant.get(i).postRef = XhsCollector.postRef(i + 1);
        markDuplicateTitles(relevant);
        task.collection.relevantCount = relevant.size();
        task.collection.selectedCount = relevant.size();
        task.ranking = Map.of("basis", "likes_desc_then_post_id", "missingLikesPolicy", "AFTER_EXISTING",
                "approximateLikesPolicy", "CONVERTED_FOR_SORT_ONLY");
        task.orchestration.checkpoint.selectedPostsSaved = true;
        repository.saveTask(task);
        events.publish(task, "status", "候选与排序完成", Map.of("candidateCount", candidates.size(),
                "relevantCount", relevant.size(), "selectedCount", relevant.size(), "progress", 0.32));

        setStage(task, context, "CLASSIFYING", TaskStatus.COLLECTING_DETAILS, 0.35);
        Map<String, Post> parentPosts = parentPosts(task);
        Map<String, List<byte[]>> rawImages = new LinkedHashMap<>();
        List<Post> successful = new ArrayList<>();
        long detailStart = System.currentTimeMillis();
        for (Post candidate : relevant) {
            context.checkCanceled();
            Post post;
            Post cached = parentPosts.get(candidate.postId);
            if (cached != null) {
                post = cached;
                post.postRef = candidate.postRef;
                post.url = candidate.url;
                post.title = candidate.title;
                post.likes = candidate.likes; post.collections = candidate.collections; post.comments = candidate.comments;
                post.collectedAt = OffsetDateTime.now();
                post.collectionStatus = "SUCCESS";
                context.cacheHits++;
                events.log(task, "复用已缓存详情：" + post.postRef);
            } else {
                try {
                    XhsCollector.DetailResult detail = collector.collectDetail(candidate);
                    post = detail.post();
                    post.postRef = candidate.postRef;
                    post.relevance = candidate.relevance; post.relevanceScore = candidate.relevanceScore;
                    post.relevanceReason = candidate.relevanceReason;
                    for (int i = 0; i < detail.images().size(); i++) {
                        ImageText pending = new ImageText();
                        pending.imageIndex = i + 1;
                        pending.sourceUrl = post.url;
                        pending.status = "PENDING";
                        post.imageText.add(pending);
                    }
                    rawImages.put(post.postRef, detail.images());
                    context.cacheMisses++;
                } catch (LoginRequiredException | NoteUnavailableException e) {
                    post = candidate;
                    post.collectionStatus = "FAILED";
                    post.failure = Map.of("stage", "COLLECTING_DETAILS",
                            "reason", e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "url", candidate.url, "xsecTokenPresent", candidate.url != null && candidate.url.contains("xsec_token="),
                            "retryable", false, "attempts", ++post.attempts);
                    repository.savePost(task.taskId, post);
                    task.orchestration.checkpoint.failedDetailPostRefs.add(post.postRef);
                    task.collection.successfulDetailCount = successful.size();
                    task.collection.failedDetailCount = task.collection.selectedCount - successful.size();
                    repository.saveTask(task);
                    events.publish(task, "error", "详情访问受限，已停止后续采集：" + post.postRef,
                            Map.of("postRef", post.postRef, "reason", e.getMessage()));
                    throw new IllegalStateException("DETAIL_ACCESS_STOPPED:" + post.postRef + ":" + e.getMessage(), e);
                } catch (Exception e) {
                    post = candidate;
                    post.collectionStatus = "FAILED";
                    post.failure = Map.of("stage", "COLLECTING_DETAILS", "reason", e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "retryable", true, "attempts", ++post.attempts);
                    events.publish(task, "error", "详情读取失败：" + post.postRef, Map.of("postRef", post.postRef, "reason", e.getMessage()));
                }
            }
            if ("SUCCESS".equals(post.collectionStatus)) {
                if (hasRealDetail(post) && post.imageText.stream().allMatch(x -> !"PENDING".equals(x.status))) {
                    repository.saveCachePost(post);
                }
                successful.add(post);
                task.orchestration.checkpoint.completedDetailPostRefs.add(post.postRef);
            } else {
                task.orchestration.checkpoint.failedDetailPostRefs.add(post.postRef);
            }
            repository.savePost(task.taskId, post);
            repository.saveTask(task);
        }
        context.detailDurationMs = System.currentTimeMillis() - detailStart;
        task.collection.successfulDetailCount = successful.size();
        task.collection.failedDetailCount = relevant.size() - successful.size();
        events.publish(task, "status", "详情采集完成", Map.of("success", successful.size(), "failed", task.collection.failedDetailCount, "progress", 0.62));
        if (successful.isEmpty()) throw new IllegalStateException("ALL_SELECTED_DETAILS_FAILED");

        setStage(task, context, "COLLECTING_DETAILS", TaskStatus.EXTRACTING_IMAGES, 0.68);
        List<Post> analyzed = extractImages(task, context, successful, rawImages);
        task.collection.successfulDetailCount = analyzed.size();
        repository.saveTask(task);

        setStage(task, context, "EXTRACTING_IMAGES", TaskStatus.GENERATING_REPORT, 0.75);
        ReportRecord report = reportAgent.generate(task, analyzed, token -> events.reportToken(task, token));
        context.checkCanceled();
        repository.saveReport(task.taskId, report);
        task.orchestration.checkpoint.reportSaved = true;
        events.publish(task, "final-answer-gate", report.finalAnswerGate, Map.of("status", report.finalAnswerGate, "failedChecks", report.failedChecks));

        if (task.parentTaskId != null) {
            setStage(task, context, "GENERATING_REPORT", TaskStatus.GENERATING_REPORT, 0.92);
            incrementalService.compare(task.taskId, task.parentTaskId);
            context.checkCanceled();
        }
        task.collection.finishedAt = OffsetDateTime.now();
        task.finishedAt = OffsetDateTime.now();
        task.status = report.failedChecks.isEmpty() && task.collection.failedDetailCount == 0
                ? TaskStatus.COMPLETED : TaskStatus.PARTIALLY_COMPLETED;
        task.orchestration.currentStage = "FINAL_ANSWER_GATE";
        task.orchestration.lastCompletedStage = "FINAL_ANSWER_GATE";
        usageTracker.get(task.taskId).finalAnswerGate = report.finalAnswerGate;
        saveMetrics(task, context);
        repository.saveTask(task);
        events.publish(task, "done", task.status.name(), Map.of("status", task.status.name()));
    }

    private List<Post> extractImages(Task task, RunContext context, List<Post> posts, Map<String, List<byte[]>> rawImages) {
        List<Post> result = new ArrayList<>();
        for (Post post : posts) {
            context.checkCanceled();
            List<byte[]> images = rawImages.getOrDefault(post.postRef, List.of());
            if (post.imageText.isEmpty() && images.isEmpty()) {
                post.coverage = new ArrayList<>(new LinkedHashSet<>(post.coverage));
                repository.savePost(task.taskId, post); result.add(post);
                continue;
            }
            List<ImageText> extracted = new ArrayList<>();
            // Details are saved with pending image records only when they were newly collected.
            // Cached posts already contain their extracted text.
            if (post.imageText.stream().allMatch(x -> !"PENDING".equals(x.status))) {
                repository.savePost(task.taskId, post); result.add(post); context.cacheHits++; continue;
            }
            post.imageText = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                ImageText pending = new ImageText();
                pending.imageIndex = i + 1; pending.sourceUrl = post.url; pending.status = "PENDING";
                post.imageText.add(pending);
            }
            for (ImageText pending : post.imageText) {
                try {
                    byte[] png = images.get(pending.imageIndex - 1);
                    ModelService.ImageTextResult r = modelService.extractImageText(task.taskId,
                            post.postRef + "-" + pending.imageIndex, png);
                    pending.text = r.text(); pending.confidence = r.confidence(); pending.status = "SUCCESS";
                    usageTracker.get(task.taskId).toolCalls.incrementAndGet();
                } catch (Exception e) {
                    pending.status = "FAILED"; pending.failureReason = e.getMessage();
                }
                extracted.add(pending);
            }
            post.imageText = extracted;
            post.coverage = new ArrayList<>(new LinkedHashSet<>(post.coverage));
            if (extracted.stream().anyMatch(x -> "SUCCESS".equals(x.status) && x.text != null && !x.text.isBlank())) post.coverage.add("IMAGE_TEXT");
            repository.savePost(task.taskId, post);
            if (hasRealDetail(post)) repository.saveCachePost(post);
            result.add(post);
        }
        return result;
    }

    private Map<String, Post> parentPosts(Task task) {
        Map<String, Post> map = new HashMap<>();
        // Parent task provides incremental reuse.
        if (task.parentTaskId != null) repository.posts(task.parentTaskId).forEach(p -> map.put(p.postId, p));
        // Current task snapshots allow WAITING_USER / retry to skip already successful details.
        repository.posts(task.taskId).forEach(p -> {
            if (isReusableDetail(p)) map.put(p.postId, p);
        });
        return map;
    }

    private static boolean isReusableDetail(Post p) {
        return "SUCCESS".equals(p.collectionStatus) && hasRealDetail(p);
    }

    private static boolean hasRealDetail(Post p) {
        if (p == null || XhsCollector.isBlockedDetailTitle(p.title)) return false;
        boolean hasBody = p.body != null && !p.body.isBlank();
        boolean hasImageText = p.imageText != null && p.imageText.stream()
                .anyMatch(x -> "SUCCESS".equals(x.status) && x.text != null && !x.text.isBlank());
        return hasBody || hasImageText;
    }

    private int ranking(Post a, Post b) {
        long av = a.likes.value == null ? Long.MIN_VALUE : a.likes.value;
        long bv = b.likes.value == null ? Long.MIN_VALUE : b.likes.value;
        if (av != bv) return Long.compare(bv, av);
        return a.postId.compareTo(b.postId);
    }

    private void markDuplicateTitles(List<Post> posts) {
        Map<String, Integer> titles = new HashMap<>();
        posts.forEach(p -> titles.merge(p.title == null ? "" : p.title, 1, Integer::sum));
        posts.forEach(p -> p.possibleDuplicateTitle = titles.getOrDefault(p.title == null ? "" : p.title, 0) > 1);
    }

    private void saveCandidates(Task task, List<Post> candidates) {
        markdownStore.writeJson(repository.taskDir(task.taskId).resolve("candidates.md"), "Candidate pool",
                Map.of("type", "candidates", "taskId", task.taskId, "version", 1), candidates);
    }

    public void setStage(Task task, RunContext context, String completedStage, TaskStatus status, double progress) {
        context.checkCanceled();
        task.orchestration.lastCompletedStage = completedStage;
        task.orchestration.currentStage = status.name();
        task.status = status;
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", status.name()); stage.put("start", OffsetDateTime.now().toString());
        stage.put("status", "RUNNING");
        context.stages.add(stage);
        repository.saveTask(task);
        events.publish(task, "status", status.name(), Map.of("status", status.name(), "stage", status.name(), "progress", progress));
    }

    public void saveMetrics(Task task, RunContext context) {
        MetricsRecord m = new MetricsRecord();
        m.taskId = task.taskId;
        m.totalDurationMs = System.currentTimeMillis() - context.startedAt;
        var u = usageTracker.get(task.taskId);
        m.queue = Map.of("waitMs", context.queueWaitMs, "browserConcurrency", 1, "queuedTasks", 0);
        m.collector = Map.of("searchDurationMs", context.searchDurationMs, "detailDurationMs", context.detailDurationMs,
                "detailVisited", task.collection.selectedCount, "detailSuccess", task.collection.successfulDetailCount,
                "retries", context.retries, "cacheHits", context.cacheHits, "cacheMisses", context.cacheMisses,
                "captchaPauses", context.captchaPauses);
        m.model = Map.of("concurrency", properties.getModel().getMaxConcurrency(), "calls", u.calls.get(),
                "agentLoopRounds", u.agentRounds.get(), "toolCalls", u.toolCalls.get(), "finalAnswerGate", u.finalAnswerGate,
                "inputTokens", u.inputTokens.get(), "outputTokens", u.outputTokens.get(), "usageSource", "API_RESPONSE");
        m.stages = context.stages;
        repository.saveMetrics(task.taskId, m);
    }
}
