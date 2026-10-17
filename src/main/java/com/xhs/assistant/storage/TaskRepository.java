package com.xhs.assistant.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhs.assistant.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

@Repository
public class TaskRepository {
    private final MarkdownStore store;
    private final ObjectMapper objectMapper;
    private final Path root;

    public TaskRepository(MarkdownStore store, ObjectMapper objectMapper,
                          @Value("${assistant.storage.root:./data}") String root) {
        this.store = store;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.findAndRegisterModules();
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path root() { return root; }
    public Path taskDir(String taskId) { return root.resolve("tasks").resolve(taskId); }

    public void saveTask(Task task) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("type", "task"); fm.put("taskId", task.taskId); fm.put("version", 1);
        store.writeJson(taskDir(task.taskId).resolve("task.md"), "Task: " + task.keyword, fm, task);
        updateIndex();
    }

    public List<Task> loadAll() {
        try (Stream<Path> stream = Files.list(root.resolve("tasks"))) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.resolve("task.md"))
                    .filter(Files::exists)
                    .map(p -> store.readJson(p, Task.class))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing((Task t) -> Optional.ofNullable(t.createdAt).orElse(OffsetDateTime.MIN)).reversed())
                    .toList();
        } catch (IOException e) { return List.of(); }
    }

    public Optional<Task> find(String taskId) {
        Task t = store.readJson(taskDir(taskId).resolve("task.md"), Task.class);
        return Optional.ofNullable(t);
    }

    public void savePost(String taskId, Post post) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("type", "post-snapshot"); fm.put("taskId", taskId);
        fm.put("postRef", post.postRef); fm.put("version", 1);
        store.writeJson(taskDir(taskId).resolve("posts").resolve(post.postRef + ".md"),
                post.postRef + ": " + post.title, fm, post);
    }

    public List<Post> posts(String taskId) {
        Path dir = taskDir(taskId).resolve("posts");
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> store.readJson(p, Post.class))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(p -> p.postRef))
                    .toList();
        } catch (IOException e) { throw new MarkdownStore.StorageException("Cannot list posts", e); }
    }

    public Optional<Post> post(String taskId, String postRef) {
        return Optional.ofNullable(store.readJson(taskDir(taskId).resolve("posts").resolve(postRef + ".md"), Post.class));
    }

    public void saveSelected(String taskId, List<Post> selected) {
        Map<String, Object> fm = Map.of("type", "selected-posts", "taskId", taskId, "version", 1);
        store.writeJson(taskDir(taskId).resolve("selected.md"), "Selected posts", fm, selected);
    }

    public List<Post> candidates(String taskId) {
        Path file = taskDir(taskId).resolve("candidates.md");
        if (!Files.exists(file)) return List.of();
        Post[] posts = store.readJson(file, Post[].class);
        return posts == null ? List.of() : Arrays.stream(posts).filter(Objects::nonNull).toList();
    }

    public Optional<List<Post>> selected(String taskId) {
        Path file = taskDir(taskId).resolve("selected.md");
        if (!Files.exists(file)) return Optional.empty();
        Post[] posts = store.readJson(file, Post[].class);
        if (posts == null) return Optional.empty();
        return Optional.of(Arrays.stream(posts)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(p -> p.postRef))
                .toList());
    }

    public void saveReport(String taskId, ReportRecord report) {
        Map<String, Object> fm = Map.of("type", "report", "taskId", taskId, "version", 1);
        store.writeJson(taskDir(taskId).resolve("report.md"), "Research report", fm, report);
    }

    public Optional<ReportRecord> report(String taskId) {
        return Optional.ofNullable(store.readJson(taskDir(taskId).resolve("report.md"), ReportRecord.class));
    }

    public void saveDiff(String taskId, DiffRecord diff) {
        Map<String, Object> fm = Map.of("type", "diff", "taskId", taskId, "version", 1);
        store.writeJson(taskDir(taskId).resolve("diff.md"), "Incremental comparison", fm, diff);
    }

    public Optional<DiffRecord> diff(String taskId) {
        return Optional.ofNullable(store.readJson(taskDir(taskId).resolve("diff.md"), DiffRecord.class));
    }

    public void saveMetrics(String taskId, MetricsRecord metrics) {
        Map<String, Object> fm = Map.of("type", "metrics", "taskId", taskId, "version", 1);
        store.writeJson(taskDir(taskId).resolve("metrics.md"), "Efficiency and usage", fm, metrics);
    }

    public Optional<MetricsRecord> metrics(String taskId) {
        return Optional.ofNullable(store.readJson(taskDir(taskId).resolve("metrics.md"), MetricsRecord.class));
    }

    public void saveQuestion(QuestionRecord q) {
        Map<String, Object> fm = Map.of("type", "qa", "taskId", q.taskId, "qaId", q.questionId, "version", 1);
        store.writeJson(taskDir(q.taskId).resolve("qa").resolve(q.questionId + ".md"), q.questionId + ": " + q.question, fm, q);
    }

    public List<QuestionRecord> questions(String taskId) {
        Path dir = taskDir(taskId).resolve("qa");
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> store.readJson(p, QuestionRecord.class))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(q -> q.questionId))
                    .toList();
        } catch (IOException e) { return List.of(); }
    }

    public Optional<QuestionRecord> question(String taskId, String questionId) {
        return Optional.ofNullable(store.readJson(taskDir(taskId).resolve("qa").resolve(questionId + ".md"), QuestionRecord.class));
    }

    public void appendEvent(String taskId, TaskEvent event) {
        Map<String, Object> fm = Map.of("type", "events", "taskId", taskId, "version", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = objectMapper.convertValue(event, Map.class);
        store.appendJsonLine(taskDir(taskId).resolve("events.md"), fm, value);
    }

    public List<TaskEvent> events(String taskId) {
        Path file = taskDir(taskId).resolve("events.md");
        if (!Files.exists(file)) return List.of();
        try {
            List<TaskEvent> result = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.startsWith("- ")) continue;
                TaskEvent e = objectMapper.readValue(line.substring(2), TaskEvent.class);
                result.add(e);
            }
            return result;
        } catch (IOException e) { return List.of(); }
    }

    public void appendModelUsage(String taskId, ModelUsageRecord row) {
        Map<String, Object> fm = Map.of("type", "model-usage", "taskId", taskId, "version", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = objectMapper.convertValue(row, Map.class);
        store.appendJsonLine(taskDir(taskId).resolve("model-usage.md"), fm, value);
    }

    public List<ModelUsageRecord> modelUsage(String taskId) {
        return List.of();
    }

    public void saveCachePost(Post post) {
        Map<String, Object> fm = Map.of("type", "post-cache", "postId", post.postId, "version", 1);
        store.writeJson(root.resolve("cache/posts").resolve(post.postId + ".md"), "Post cache", fm, post);
    }

    public Optional<Post> cachePost(String postId) {
        return Optional.ofNullable(store.readJson(root.resolve("cache/posts").resolve(postId + ".md"), Post.class));
    }

    public void saveAsset(String taskId, String name, byte[] bytes) {
        store.writeBytes(taskDir(taskId).resolve("attachments").resolve(name), bytes);
    }

    private void updateIndex() {
        List<Task> tasks = loadAll();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task t : tasks) {
            rows.add(new LinkedHashMap<>(Map.of(
                    "taskId", t.taskId,
                    "keyword", t.keyword,
                    "normalizedKeyword", t.normalizedKeyword,
                    "status", t.status.name(),
                    "createdAt", String.valueOf(t.createdAt),
                    "resultPath", "tasks/" + t.taskId + "/report.md"
            )));
        }
        Map<String, Object> fm = Map.of("type", "index", "version", 1, "updatedAt", OffsetDateTime.now().toString());
        store.writeJson(root.resolve("index.md"), "Task index", fm, Map.of("tasks", rows));
    }
}
