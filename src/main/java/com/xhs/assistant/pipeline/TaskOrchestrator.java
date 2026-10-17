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
        usageTracker.clear(task.taskId);
        task.startedAt = OffsetDateTime.now();
        task.collection.startedAt = task.startedAt;
        setStage(task, context, "QUEUED", TaskStatus.WAITING_BROWSER, 0.02);

        List<Post> relevant;
        if (task.orchestration.checkpoint.selectedPostsSaved) {
            relevant = restoreSelectedPosts(task);
            task.collection.relevantCount = relevant.size();
            task.collection.selectedCount = relevant.size();
            long successCount = relevant.stream().filter(p -> "SUCCESS".equals(p.collectionStatus)).count();
            task.collection.successfulDetailCount = (int) successCount;
            task.collection.failedDetailCount = relevant.size() - (int) successCount;
            repository.saveTask(task);
            events.log(task, "复用已保存的入选帖子：" + relevant.size()
                    + " 条；成功详情 " + successCount + " 条，待补采 " + (relevant.size() - successCount) + " 条");
        } else {
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
            relevant = candidates.stream()
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
            task.orchestration.checkpoint.selectedPostRefs = relevant.stream().map(p -> p.postRef).toList();
            task.orchestration.checkpoint.selectedPostIds = relevant.stream().map(p -> p.postId).toList();
            task.orchestration.checkpoint.completedDetailPostRefs.clear();
            task.orchestration.checkpoint.failedDetailPostRefs.clear();
            repository.saveSelected(task.taskId, relevant);
            repository.saveTask(task);
            events.publish(task, "status", "候选与排序完成", Map.of("candidateCount", candidates.size(),
                    "relevantCount", relevant.size(), "selectedCount", relevant.size(), "progress", 0.32));
        }

        if (relevant.isEmpty()) {
            finishEmptySearch(task, context);
            return;
        }

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
                        repository.saveAsset(task.taskId, imageAssetName(post.postRef, i + 1), detail.images().get(i));
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
                    addUnique(task.orchestration.checkpoint.failedDetailPostRefs, post.postRef);
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
                addUnique(task.orchestration.checkpoint.completedDetailPostRefs, post.postRef);
            } else {
                addUnique(task.orchestration.checkpoint.failedDetailPostRefs, post.postRef);
            }
            repository.savePost(task.taskId, post);
            repository.saveTask(task);
        }
        context.detailDurationMs = System.currentTimeMillis() - detailStart;
        task.collection.successfulDetailCount = successful.size();
        task.collection.failedDetailCount = relevant.size() - successful.size();
        events.publish(task, "status", "详情采集完成", Map.of("success", successful.size(), "failed", task.collection.failedDetailCount, "progress", 0.62));
        if (successful.isEmpty() && !relevant.isEmpty()) {
            task.status = TaskStatus.PARTIALLY_COMPLETED;
            task.failureCode = "ALL_SELECTED_DETAILS_FAILED";
            task.failureMessage = "入选帖子详情全部读取失败";
            saveMetrics(task, context);
            repository.saveTask(task);
            events.publish(task, "error", "入选帖子详情全部读取失败", Map.of("selected", relevant.size()));
            return;
        }

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

    private List<Post> restoreSelectedPosts(Task task) {
        List<Post> snapshot = repository.selected(task.taskId).orElseGet(() -> {
            List<String> refs = task.orchestration.checkpoint.selectedPostRefs;
            if (refs == null || refs.isEmpty()) return List.of();
            Map<String, Post> candidates = new LinkedHashMap<>();
            repository.candidates(task.taskId).forEach(p -> candidates.put(p.postRef, p));
            repository.posts(task.taskId).forEach(p -> candidates.put(p.postRef, p));
            return refs.stream().map(candidates::get).filter(Objects::nonNull).toList();
        });
        Map<String, Post> executed = new HashMap<>();
        repository.posts(task.taskId).forEach(p -> executed.put(p.postRef, p));

        List<Post> restored = new ArrayList<>();
        for (Post selected : snapshot) {
            Post existing = executed.get(selected.postRef);
            Post base = selected == null ? new Post() : selected;
            if (existing != null) {
                // Preserve successful body/OCR or failed attempts while refreshing signed URL and list metrics.
                existing.url = base.url;
                existing.xsecToken = base.xsecToken;
                existing.title = firstNonBlank(existing.title, base.title);
                existing.likes = firstMetric(existing.likes, base.likes);
                existing.collections = firstMetric(existing.collections, base.collections);
                existing.comments = firstMetric(existing.comments, base.comments);
                restored.add(existing);
            } else {
                restored.add(base);
            }
        }
        return restored.stream().filter(p -> p.postRef != null).sorted(Comparator.comparing(p -> p.postRef)).toList();
    }

    private void finishEmptySearch(Task task, RunContext context) {
        ReportRecord report = new ReportRecord();
        report.taskId = task.taskId;
        report.keyword = task.keyword;
        report.generatedAt = OffsetDateTime.now();
        report.scope = "小红书本次搜索返回 " + task.collection.distinctPostCount
                + " 个不同帖子；相关帖子 0 条，入选 Top 0 条。图片OCR：成功0张，失败0张，记录0张；视频音画未处理。";
        report.reportMarkdown = """
                # 关键词研究报告

                ## 1. 范围与限制
                本次搜索范围内没有找到相关帖子。图片OCR成功0张，失败0张，记录0张；视频音画未处理。

                ## 2. 主要讨论主题
                本次搜索范围内没有可分析的相关帖子。

                ## 3. 反复出现的观点
                无相关材料，无法归纳。

                ## 4. 明显分歧
                无相关材料，无法归纳。

                ## 5. 场景、体验、问题与方法
                无相关材料，无法归纳。

                ## 6. 证据类型判断
                无相关材料，无法判断。

                ## 7. 本批材料无法回答的问题
                本次搜索没有返回相关帖子，因此无法回答研究问题。

                ## 8. 热门帖子列表
                本次搜索范围内没有相关帖子。
                """;
        report.finalAnswerGate = "PASSED";
        report.failedChecks = List.of();
        report.citationValidation = Map.of("status", "NOT_APPLICABLE", "invalidCitations", List.of());
        repository.saveReport(task.taskId, report);

        task.collection.finishedAt = OffsetDateTime.now();
        task.finishedAt = OffsetDateTime.now();
        task.status = TaskStatus.COMPLETED;
        task.orchestration.currentStage = "FINAL_ANSWER_GATE";
        task.orchestration.lastCompletedStage = "FINAL_ANSWER_GATE";
        task.orchestration.checkpoint.reportSaved = true;
        usageTracker.get(task.taskId).finalAnswerGate = "PASSED";
        saveMetrics(task, context);
        repository.saveTask(task);
        events.publish(task, "done", "COMPLETED", Map.of("status", "COMPLETED", "reason", "NO_RELEVANT_POSTS"));
    }

    private static <T> void addUnique(List<T> list, T value) {
        if (!list.contains(value)) list.add(value);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static MetricValue firstMetric(MetricValue a, MetricValue b) {
        if (a != null && !a.missing) return a;
        if (b != null && !b.missing) return b;
        return MetricValue.missing();
    }

    private List<Post> extractImages(Task task, RunContext context, List<Post> posts, Map<String, List<byte[]>> rawImages) {
        List<Post> result = new ArrayList<>();
        for (Post post : posts) {
            context.checkCanceled();
            Map<Integer, byte[]> imageBytes = loadImageAssets(task.taskId, post.postRef);
            List<byte[]> raw = rawImages.getOrDefault(post.postRef, List.of());
            for (int i = 0; i < raw.size(); i++) imageBytes.put(i + 1, raw.get(i));

            if (post.imageText.isEmpty()) {
                for (Integer index : new TreeSet<>(imageBytes.keySet())) {
                    ImageText pending = new ImageText();
                    pending.imageIndex = index;
                    pending.sourceUrl = post.url;
                    pending.status = "PENDING";
                    post.imageText.add(pending);
                }
            }

            for (ImageText pending : post.imageText) {
                if (!"PENDING".equals(pending.status)) continue;
                byte[] png = imageBytes.get(pending.imageIndex);
                if (png == null) {
                    pending.status = "FAILED";
                    pending.failureReason = "IMAGE_ASSET_MISSING";
                    continue;
                }
                try {
                    ModelService.ImageTextResult r = modelService.extractImageText(task.taskId,
                            post.postRef + "-" + pending.imageIndex, png);
                    pending.text = r.text();
                    pending.confidence = r.confidence();
                    pending.status = "SUCCESS";
                    pending.failureReason = null;
                    usageTracker.get(task.taskId).toolCalls.incrementAndGet();
                } catch (Exception e) {
                    pending.status = "FAILED";
                    pending.failureReason = e.getMessage();
                }
            }

            post.coverage = new ArrayList<>(new LinkedHashSet<>(post.coverage));
            if (post.imageText.stream().anyMatch(x -> "SUCCESS".equals(x.status) && x.text != null && !x.text.isBlank())) {
                post.coverage.add("IMAGE_TEXT");
            }
            updateImageCoverageNote(post);
            repository.savePost(task.taskId, post);
            if (hasRealDetail(post)) repository.saveCachePost(post);
            result.add(post);
        }
        return result;
    }

    private Map<Integer, byte[]> loadImageAssets(String taskId, String postRef) {
        Map<Integer, byte[]> result = new HashMap<>();
        for (int index = 1; index <= 99; index++) {
            try {
                Path asset = repository.taskDir(taskId)
                        .resolve("attachments")
                        .resolve(imageAssetName(postRef, index));
                if (Files.exists(asset)) result.put(index, Files.readAllBytes(asset));
            } catch (Exception ignored) {
                // A missing/unreadable asset is recorded on the ImageText row, never silently dropped.
            }
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

    private static void updateImageCoverageNote(Post post) {
        int success = 0, failed = 0;
        for (ImageText image : post.imageText) {
            if ("SUCCESS".equals(image.status) && image.text != null && !image.text.isBlank()) success++;
            else if (!"PENDING".equals(image.status)) failed++;
        }
        String imageNote = post.imageText.isEmpty()
                ? "未提取到可处理图片"
                : "图片OCR成功" + success + "张/失败" + failed + "张/记录" + post.imageText.size() + "张";
        post.coverageNote = imageNote + "；视频音画未处理";
    }

    private static String imageAssetName(String postRef, int index) {
        return "images/" + postRef + "-" + String.format("%03d", index) + ".png";
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

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
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
        Map<String, Object> currentModel = new LinkedHashMap<>(Map.of(
                "concurrency", properties.getModel().getMaxConcurrency(), "calls", u.calls.get(),
                "agentLoopRounds", u.agentRounds.get(), "toolCalls", u.toolCalls.get(),
                "finalAnswerGate", u.finalAnswerGate, "inputTokens", u.inputTokens.get(),
                "outputTokens", u.outputTokens.get(), "usageSource", "API_RESPONSE"));
        m.model = currentModel;

        MetricsRecord previous = repository.metrics(task.taskId).orElse(null);
        Map<String, Object> previousCumulative = previous == null ? Map.of() : previous.cumulativeModel;
        Map<String, Object> cumulative = new LinkedHashMap<>();
        for (String key : List.of("calls", "agentLoopRounds", "toolCalls", "inputTokens", "outputTokens")) {
            cumulative.put(key, longValue(previousCumulative.get(key)) + longValue(currentModel.get(key)));
        }
        cumulative.put("finalAnswerGate", u.finalAnswerGate);
        cumulative.put("usageSource", "API_RESPONSE");
        m.cumulativeModel = cumulative;

        m.runs = new ArrayList<>(previous == null ? List.of() : previous.runs);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("startedAt", String.valueOf(task.startedAt));
        run.put("finishedAt", String.valueOf(OffsetDateTime.now()));
        run.put("totalDurationMs", m.totalDurationMs);
        run.put("model", currentModel);
        m.runs.add(run);

        m.stages = context.stages;
        repository.saveMetrics(task.taskId, m);
    }
}
