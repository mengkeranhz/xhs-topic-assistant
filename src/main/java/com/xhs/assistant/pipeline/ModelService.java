package com.xhs.assistant.pipeline;

import com.fasterxml.jackson.databind.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.llm.*;
import com.xhs.assistant.llm.LlmTypes.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

@Service
public class ModelService {
    private final LlmClientSelector selector;
    private final AssistantProperties properties;
    private final ObjectMapper mapper;
    private final UsageTracker usageTracker;

    public ModelService(LlmClientSelector selector, AssistantProperties properties, ObjectMapper mapper, UsageTracker usageTracker) {
        this.selector = selector; this.properties = properties; this.mapper = mapper.copy();
        this.usageTracker = usageTracker;
    }

    public boolean offlineFallback() { return properties.getModel().isAllowOfflineFallback(); }

    public List<Map<String, Object>> classifyRelevance(String taskId, String keyword, List<Map<String, Object>> summaries) {
        final int batchSize = 15;
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i += batchSize) {
            List<Map<String, Object>> batch = summaries.subList(i, Math.min(summaries.size(), i + batchSize));
            all.addAll(classifyRelevanceBatch(taskId, keyword, batch, i / batchSize + 1));
        }
        return all;
    }

    private List<Map<String, Object>> classifyRelevanceBatch(String taskId, String keyword,
                                                              List<Map<String, Object>> summaries,
                                                              int batchNumber) {
        String system = """
                Classify search results for a research task. Return ONLY a JSON array.
                Every input post must appear exactly once in the output.
                Each item: {"postId":string,"label":"RELEVANT|MAYBE|IRRELEVANT","score":number,"reason":string}.
                Consider keyword identity, version mismatch, same-name ambiguity, ads, and actual topic relevance.
                Keep each reason under 40 Chinese characters.
                """;
        String prompt = "Keyword: " + keyword + "\n\nBatch " + batchNumber + "\nResults:\n" + json(summaries);
        try {
            LlmClient client = selector.current().orElseThrow(() -> new IllegalStateException("Unknown model provider"));
            ChatResult result = client.chat(new ChatRequest(system, List.of(Message.user(prompt)), 0.0, 2500, List.of()), false);
            usageTracker.usage(taskId, result.usage());
            JsonNode node = parseJson(result.text());
            Map<String, Map<String, Object>> byPostId = new LinkedHashMap<>();
            findClassificationArray(node).forEach(n -> {
                Map<String, Object> row = mapper.convertValue(n, Map.class);
                Object postId = row.get("postId");
                if (postId != null) byPostId.put(String.valueOf(postId), row);
            });
            List<Map<String, Object>> fallback = heuristicRelevance(keyword, summaries);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < summaries.size(); i++) {
                String postId = String.valueOf(summaries.get(i).get("postId"));
                Map<String, Object> row = byPostId.get(postId);
                if (row == null) row = fallback.get(i);
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            return heuristicRelevance(keyword, summaries);
        }
    }

    private JsonNode findClassificationArray(JsonNode node) {
        if (node == null) return mapper.createArrayNode();
        if (node.isArray() && isClassificationArray(node)) return node;
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findClassificationArray(child);
                if (isClassificationArray(found)) return found;
            }
        }
        return mapper.createArrayNode();
    }

    private boolean isClassificationArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty() && node.get(0).has("postId");
    }

    private List<Map<String, Object>> heuristicRelevance(String keyword, List<Map<String, Object>> summaries) {
        List<String> terms = terms(keyword);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : summaries) {
            String text = (String.valueOf(s.getOrDefault("title", "")) + " " + s.getOrDefault("summary", "")).toLowerCase(Locale.ROOT);
            long hits = terms.stream().filter(text::contains).count();
            String label = hits == terms.size() ? "RELEVANT" : hits > 0 ? "MAYBE" : "IRRELEVANT";
            out.add(Map.of("postId", String.valueOf(s.get("postId")), "label", label,
                    "score", Math.min(1.0, (double) hits / Math.max(1, terms.size())), "reason", "offline keyword overlap"));
        }
        return out;
    }

    public ImageTextResult extractImageText(String taskId, String filename, byte[] imageBytes) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ImageData data = new ImageData("image/png", base64);
        try {
            LlmClient client = selector.current().orElseThrow();
            ChatResult result = client.extractImageText("Extract visible text from " + filename + ".", List.of(data));
            usageTracker.usage(taskId, result.usage());
            JsonNode node = parseJson(result.text());
            return new ImageTextResult(node.path("text").asText(""), node.path("confidence").asText("UNKNOWN"));
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            return new ImageTextResult("", "LOW");
        }
    }

    public record ImageTextResult(String text, String confidence) {}

    public Map<String, Object> generateFiveW1H(String taskId, String keyword, Map<String, Object> evidence) {
        String system = """
                Extract a strict 5W1H analysis. Return ONLY JSON with top-level keys:
                who, what, when, where, why, how. Each value is {"text":string,"evidence":string[]}.
                Use "未提及" when source material does not state it. Do not infer identities.
                """;
        try {
            LlmClient client = selector.current().orElseThrow();
            ChatResult result = client.chat(new ChatRequest(system, List.of(Message.user(json(evidence))), 0.1, 1600, List.of()), false);
            usageTracker.usage(taskId, result.usage());
            return mapper.convertValue(parseJson(result.text()), Map.class);
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            return offlineFiveW1H(evidence);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> offlineFiveW1H(Map<String, Object> evidence) {
        String notMentioned = "未提及";
        String ref = String.valueOf(evidence.getOrDefault("postRef", ""));
        return Map.of(
                "who", Map.of("text", String.valueOf(evidence.getOrDefault("author", notMentioned)), "evidence", List.of(ref)),
                "what", Map.of("text", String.valueOf(evidence.getOrDefault("title", notMentioned)), "evidence", List.of(ref)),
                "when", Map.of("text", String.valueOf(evidence.getOrDefault("publishedAt", notMentioned)), "evidence", List.of(ref)),
                "where", Map.of("text", notMentioned, "evidence", List.of()),
                "why", Map.of("text", notMentioned, "evidence", List.of()),
                "how", Map.of("text", String.valueOf(evidence.getOrDefault("body", notMentioned)), "evidence", List.of(ref))
        );
    }

    public List<Map<String, Object>> planReport(String taskId, String keyword, List<Map<String, Object>> indexes) {
        String system = """
                Create a research plan. Return ONLY JSON:
                {"reportGoals":string[],"neededEvidence":[{"query":string,"postRefs":string[]}],"riskChecks":string[]}.
                Keep it under 8 evidence queries.
                """;
        try {
            LlmClient client = selector.current().orElseThrow();
            ChatResult result = client.chat(new ChatRequest(system, List.of(Message.user("Keyword: " + keyword + "\nPosts:\n" + json(indexes))), 0.1, 1200, List.of()), false);
            usageTracker.usage(taskId, result.usage());
            JsonNode n = parseJson(result.text());
            JsonNode needed = n.path("neededEvidence");
            if (!needed.isArray() || needed.isEmpty()) {
                return List.of(Map.of("query", keyword, "postRefs", List.of()));
            }
            List<Map<String, Object>> result2 = mapper.convertValue(needed,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return result2 == null ? List.of(Map.of("query", keyword, "postRefs", List.of())) : result2;
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            return List.of(Map.of("query", keyword, "postRefs", List.of()));
        }
    }

    public void synthesizeReport(String taskId, String system, String prompt, Consumer<String> tokenConsumer) {
        LlmClient client = selector.current().orElseThrow();
        client.stream(new ChatRequest(system, List.of(Message.user(prompt)), properties.getModel().getTemperature(), 6000, List.of()),
                new LlmTypes.StreamHandler() {
                    @Override public void text(String delta) { tokenConsumer.accept(delta); }
                    @Override public void usage(LlmTypes.Usage usage) { usageTracker.usage("current", usage); }
                });
    }

    public QuestionRecord answerQuestion(String taskId, QuestionRecord input, List<Map<String, Object>> evidence) {
        String system = """
                Answer only from supplied task evidence. Cite postRef values like [P001].
                If evidence is insufficient, say so. Return ONLY JSON:
                {"answer":string,"citations":string[]}.
                """;
        try {
            LlmClient client = selector.current().orElseThrow();
            ChatResult result = client.chat(new ChatRequest(system, List.of(Message.user("Question: " + input.question + "\nEvidence:\n" + json(evidence))), 0.1, 1800, List.of()), false);
            usageTracker.usage(taskId, result.usage());
            JsonNode n = parseJson(result.text());
            input.answer = n.path("answer").asText("");
            n.path("citations").forEach(c -> input.citations.add(c.asText()));
            input.status = "COMPLETED";
            return input;
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            input.answer = "离线模式无法生成可靠答案：当前材料不足以在不调用模型的情况下回答。";
            input.status = "COMPLETED";
            return input;
        }
    }

    private JsonNode parseJson(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception ignored) {
            int a = Math.max(text.indexOf('{'), text.indexOf('['));
            int b = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
            if (a >= 0 && b > a) {
                try { return mapper.readTree(text.substring(a, b + 1)); }
                catch (Exception ignored2) {}
            }
            throw new IllegalArgumentException("Model did not return JSON: " + text);
        }
    }

    private String json(Object value) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

public String repairReport(String taskId, String report, List<String> failedChecks, List<Map<String, Object>> evidence) {
        String system = "Repair a Chinese research report. Preserve valid content. Return the complete corrected Markdown only.";
        String prompt = "Failed checks:\n" + String.join("\n", failedChecks) + "\n\nCurrent report:\n" + report + "\n\nEvidence:\n" + json(evidence);
        try {
            LlmClient client = selector.current().orElseThrow();
            ChatResult result = client.chat(new ChatRequest(system, List.of(Message.user(prompt)), 0.1, 6000, List.of()), false);
            usageTracker.usage(taskId, result.usage());
            return result.text();
        } catch (Exception e) {
            if (!offlineFallback()) throw e;
            return report + "\n\n## Final Answer Gate 修复说明\n\n" + String.join("；", failedChecks) + "\n";
        }
    }

    private static List<String> terms(String keyword) {
        return Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("[\\s,，、/|]+"))
                .filter(s -> !s.isBlank()).distinct().toList();
    }
}
