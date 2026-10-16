package com.xhs.assistant.pipeline;

import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

@Component
public class ReportAgent {
    private final ModelService modelService;
    private final UsageTracker usageTracker;
    private final AssistantProperties properties;

    public ReportAgent(ModelService modelService, UsageTracker usageTracker, AssistantProperties properties) {
        this.modelService = modelService; this.usageTracker = usageTracker; this.properties = properties;
    }

    public ReportRecord generate(Task task, List<Post> posts, Consumer<String> tokens) {
        usageTracker.get(task.taskId).agentRounds.incrementAndGet();
        List<Map<String, Object>> evidence = evidencePacks(posts);
        List<Map<String, Object>> index = new ArrayList<>();
        posts.forEach(p -> index.add(new LinkedHashMap<>(Map.of(
                "postRef", p.postRef, "title", String.valueOf(p.title), "author", String.valueOf(p.author),
                "likesRaw", String.valueOf(p.likes.raw), "coverage", p.coverage))));

        List<Map<String, Object>> plan = modelService.planReport(task.taskId, task.keyword, index);
        if (plan == null || plan.isEmpty()) plan = List.of(Map.of("query", task.keyword, "postRefs", List.of()));
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> planTrace = new LinkedHashMap<>();
        planTrace.put("stage", "PLAN");
        planTrace.put("queries", plan);
        trace.add(planTrace);

        Map<String, List<Map<String, Object>>> selected = new LinkedHashMap<>();
        int rounds = Math.min(plan.size(), properties.getModel().getMaxAgentRounds());
        for (int i = 0; i < rounds; i++) {
            String query = String.valueOf(plan.get(i).getOrDefault("query", task.keyword));
            List<Map<String, Object>> found = searchEvidence(evidence, query);
            selected.put(query, found);
            usageTracker.get(task.taskId).toolCalls.incrementAndGet();
            trace.add(Map.of("stage", "TOOL", "round", i + 1, "tool", "search_evidence", "query", query, "results", found.size()));
        }

        StringBuilder report = new StringBuilder();
        String system = """
                You are a careful Chinese research analyst. Write a Markdown report using ONLY supplied post evidence.
                Required sections, in this order:
                # 关键词研究报告
                ## 1. 范围与限制
                ## 2. 主要讨论主题
                ## 3. 反复出现的观点
                ## 4. 明显分歧
                ## 5. 场景、体验、问题与方法
                ## 6. 证据类型判断
                ## 7. 本批材料无法回答的问题
                ## 8. 热门帖子列表

                Requirements:
                - Clearly state this is only the current search result scope, not the whole site.
                - Compare multiple posts; do not merely concatenate summaries.
                - Distinguish repeated observations, disagreements, author self-reported experience, secondhand claims, predictions, promotion, and unverifiable claims.
                - Every key conclusion must cite one or more valid postRef values, e.g. [P001].
                - Missing or approximate metrics must not be treated as exact or zero.
                - In “范围与限制”, explicitly state image OCR success/failure counts and that video audio/video was not processed.
                - Do not infer content from failed image OCR.
                - If something cannot be answered from the evidence, explicitly say so.
                - Write in Chinese and keep the report readable.
                """;
        String prompt = "Keyword: " + task.keyword + "\n\nSearch scope:\n" + scope(task, posts) +
                "\n\nSelected evidence by query:\n" + selected + "\n\nFull evidence packs:\n" + evidence;
        try {
            modelService.synthesizeReport(task.taskId, system, prompt, token -> {
                report.append(token);
                tokens.accept(token);
            });
        } catch (Exception e) {
            if (!properties.getModel().isAllowOfflineFallback()) throw e;
            String offline = offlineReport(task, posts);
            report.append(offline);
            tokens.accept(offline);
        }

        List<String> failures = gate(report.toString(), posts);
        int repairs = 0;
        while (!failures.isEmpty() && repairs < properties.getModel().getMaxFinalRepairs()) {
            String original = report.toString();
            String repaired = modelService.repairReport(task.taskId, original, failures, evidence);
            report.setLength(0);
            report.append(repaired);
            repairs++;
            failures = gate(report.toString(), posts);
        }

        ReportRecord record = new ReportRecord();
        record.taskId = task.taskId; record.keyword = task.keyword; record.generatedAt = java.time.OffsetDateTime.now();
        record.scope = scope(task, posts);
        posts.forEach(p -> record.analyzedPostRefs.add(p.postRef));
        record.reportMarkdown = report.toString();
        record.agentTrace = trace;
        record.failedChecks = failures;
        record.finalAnswerGate = failures.isEmpty() ? "PASSED" : "FAILED";
        record.citationValidation = Map.of("status", failures.isEmpty() ? "PASSED" : "FAILED", "invalidCitations", invalidCitations(report.toString(), posts));
        var u = usageTracker.get(task.taskId);
        record.modelUsage = Map.of("calls", u.calls.get(), "agentLoopRounds", u.agentRounds.get(),
                "toolCalls", u.toolCalls.get(), "inputTokens", u.inputTokens.get(), "outputTokens", u.outputTokens.get(),
                "usageSource", "API_RESPONSE");
        return record;
    }

    private String scope(Task task, List<Post> posts) {
        return "小红书本次搜索返回前 " + task.collection.distinctPostCount + " 个不同帖子中的相关 Top " +
                task.collection.selectedCount + "；实际成功读取详情 " + task.collection.successfulDetailCount +
                " 条。" + ocrScopeSummary(posts);
    }

    private static Map<String, Object> imageOcrStats(Post p) {
        int success = 0, failed = 0;
        for (ImageText image : p.imageText) {
            if ("SUCCESS".equals(image.status) && image.text != null && !image.text.isBlank()) success++;
            else if (!"PENDING".equals(image.status)) failed++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("success", success); stats.put("failed", failed); stats.put("recorded", p.imageText.size());
        return stats;
    }

    private static String ocrScopeSummary(List<Post> posts) {
        int success = 0, failed = 0, recorded = 0;
        for (Post post : posts) {
            for (ImageText image : post.imageText) {
                recorded++;
                if ("SUCCESS".equals(image.status) && image.text != null && !image.text.isBlank()) success++;
                else if (!"PENDING".equals(image.status)) failed++;
            }
        }
        return "图片OCR：成功" + success + "张，失败" + failed + "张，记录" + recorded + "张；视频音画未处理。";
    }

    private List<Map<String, Object>> evidencePacks(List<Post> posts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Post p : posts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("postRef", p.postRef); m.put("postId", p.postId); m.put("url", p.url);
            m.put("title", p.title); m.put("author", p.author); m.put("publishedAt", p.publishedAt);
            m.put("body", truncate(p.body, 4000));
            m.put("imageText", p.imageText.stream()
                    .filter(t -> "SUCCESS".equals(t.status) && t.text != null && !t.text.isBlank())
                    .map(t -> t.text).toList());
            m.put("imageOcr", imageOcrStats(p));
            m.put("likesRaw", p.likes.raw); m.put("likesValue", p.likes.value); m.put("likesPrecision", p.likes.precision);
            m.put("collectionsRaw", p.collections.raw); m.put("commentsRaw", p.comments.raw);
            m.put("coverage", p.coverage); m.put("coverageNote", p.coverageNote);
            m.put("contentHash", p.contentHash);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> searchEvidence(List<Map<String, Object>> evidence, String query) {
        List<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s,，、/|]+"))
                .filter(s -> !s.isBlank()).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : evidence) {
            String text = String.valueOf(item.getOrDefault("title", "")) + " " + item.getOrDefault("body", "") + " " +
                    item.getOrDefault("imageText", "");
            String lower = text.toLowerCase(Locale.ROOT);
            if (terms.stream().anyMatch(lower::contains)) out.add(item);
        }
        if (out.isEmpty()) out.addAll(evidence.stream().limit(5).toList());
        return out;
    }

    private List<String> gate(String report, List<Post> posts) {
        List<String> failures = new ArrayList<>();
        List<String> required = List.of("范围与限制", "主要讨论主题", "反复出现的观点", "明显分歧",
                "场景、体验、问题与方法", "证据类型判断", "本批材料无法回答的问题", "热门帖子列表");
        for (String s : required) if (!report.contains(s)) failures.add("缺少章节: " + s);
        if (report.contains("全站最热")) failures.add("错误表述为全站范围");
        if (!report.contains("图片OCR")) failures.add("范围与限制缺少图片OCR覆盖说明");
        if (!report.contains("视频音画未处理")) failures.add("范围与限制缺少视频未处理说明");
        if (invalidCitations(report, posts).isEmpty() && !Pattern.compile("\\[P\\d{3}\\]").matcher(report).find())
            failures.add("缺少 postRef 引用");
        failures.addAll(invalidCitations(report, posts).stream().map(x -> "无效引用: " + x).toList());
        return failures;
    }

    private List<String> invalidCitations(String report, List<Post> posts) {
        Set<String> valid = new HashSet<>();
        posts.forEach(p -> valid.add(p.postRef));
        Matcher m = Pattern.compile("\\[(P\\d{3})\\]").matcher(report);
        List<String> bad = new ArrayList<>();
        while (m.find()) if (!valid.contains(m.group(1))) bad.add(m.group(1));
        return bad;
    }

    private String truncate(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max); }

    private String offlineReport(Task task, List<Post> posts) {
        StringBuilder b = new StringBuilder("# 关键词研究报告\n\n## 1. 范围与限制\n").append(scope(task, posts))
                .append("\n\n当前处于离线降级模式，本报告仅根据已采集标题、正文和成功图片文字做保守归纳，不进行跨帖观点归纳。")
                .append("\n\n").append(ocrScopeSummary(posts)).append("\n");
        b.append("\n## 2. 主要讨论主题\n");
        posts.forEach(p -> b.append("- ").append(nullSafe(p.title)).append(" [").append(p.postRef).append("]\n"));
        b.append("\n## 3. 反复出现的观点\n- 离线模式无法可靠统计跨帖重复观点。\n");
        b.append("\n## 4. 明显分歧\n- 离线模式无法可靠判断明显分歧。\n");
        b.append("\n## 5. 场景、体验、问题与方法\n");
        posts.forEach(p -> b.append("- ").append(truncate(p.body, 180).replace("\n", " ")).append(" [").append(p.postRef).append("]\n"));
        b.append("\n## 6. 证据类型判断\n- 仅根据页面材料判断，未验证事实。点赞数只表示热度，不表示真实性。\n");
        b.append("\n## 7. 本批材料无法回答的问题\n- 未调用模型时无法归纳深层争议、隐含动机和证据可信度。\n");
        b.append("\n## 8. 热门帖子列表\n");
        posts.forEach(p -> b.append("- [").append(p.postRef).append("] ")
                .append(nullSafe(p.title)).append("；点赞原文: ").append(p.likes.raw == null ? "缺失" : p.likes.raw).append("\n"));
        return b.toString();
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
}
