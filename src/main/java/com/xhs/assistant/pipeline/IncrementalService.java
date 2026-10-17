package com.xhs.assistant.pipeline;

import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.TaskRepository;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class IncrementalService {
    private final TaskRepository repository;

    public IncrementalService(TaskRepository repository) { this.repository = repository; }

    public DiffRecord compare(String taskId, String parentTaskId) {
        List<Post> current = repository.posts(taskId);
        List<Post> previous = repository.posts(parentTaskId);
        Map<String, Post> previousMap = new HashMap<>();
        previous.forEach(p -> previousMap.put(p.postId, p));
        Map<String, Post> currentMap = new HashMap<>();
        current.forEach(p -> currentMap.put(p.postId, p));

        DiffRecord d = new DiffRecord();
        d.taskId = taskId; d.baseTaskId = parentTaskId;
        current.stream().filter(p -> !previousMap.containsKey(p.postId)).forEach(p -> {
            d.newPosts.add(p.postRef);
            d.newPostDetails.add(postDetail(p));
        });
        previous.stream().filter(p -> !currentMap.containsKey(p.postId)).forEach(p -> {
            d.missingPosts.add(p.postRef);
            d.missingPostDetails.add(postDetail(p));
        });

        for (Post now : current) {
            Post old = previousMap.get(now.postId);
            if (old == null) continue;
            boolean changed = !Objects.equals(old.contentHash, now.contentHash);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("postRef", now.postRef);
            row.put("contentChanged", changed);
            if (!Objects.equals(old.likes.value, now.likes.value)) {
                Map<String, Object> likes = new LinkedHashMap<>();
                likes.put("previous", metric(old.likes)); likes.put("current", metric(now.likes));
                if (old.likes.value != null && now.likes.value != null) likes.put("deltaValue", now.likes.value - old.likes.value);
                row.put("likes", likes);
            }
            if (!Objects.equals(old.collections.value, now.collections.value)) row.put("collections", Map.of("previous", metric(old.collections), "current", metric(now.collections)));
            if (!Objects.equals(old.comments.value, now.comments.value)) row.put("comments", Map.of("previous", metric(old.comments), "current", metric(now.comments)));
            if (!((Boolean) row.getOrDefault("contentChanged", false)) && row.size() > 2) d.metricChanges.add(row);
            else if ((Boolean) row.getOrDefault("contentChanged", false)) d.metricChanges.add(row);
        }

        for (String ref : d.newPosts) currentMap.values().stream().filter(p -> p.postRef.equals(ref)).findFirst()
                .ifPresent(p -> d.newOpinions.add("新增帖子 " + ref + "：" + p.title));
        int reusedPosts = (int) current.stream().filter(p -> previousMap.containsKey(p.postId)).count();
        int reusedOcr = (int) current.stream().filter(p -> previousMap.containsKey(p.postId) && !p.imageText.isEmpty()).count();
        int reused5w = (int) current.stream().filter(p -> previousMap.containsKey(p.postId) && p.fiveW1H != null).count();
        d.reused = Map.of("posts", reusedPosts, "imageOcr", reusedOcr, "fiveW1H", reused5w);
        repository.saveDiff(taskId, d);
        return d;
    }

    private static Map<String, Object> postDetail(Post p) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("postRef", p.postRef);
        detail.put("postId", p.postId);
        detail.put("title", p.title);
        detail.put("url", p.url);
        return detail;
    }

    private Map<String, Object> metric(MetricValue v) {
        return Map.of("raw", String.valueOf(v.raw), "value", v.value == null ? "" : v.value, "precision", v.precision);
    }
}
