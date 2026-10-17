package com.xhs.assistant.pipeline;

import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiveW1HConcurrencyTest {
    @Mock private TaskRepository repository;
    @Mock private TaskOrchestrator orchestrator;
    @Mock private TaskEventPublisher events;
    @Mock private ModelService modelService;

    @Test
    void shouldDeduplicateConcurrentRequestsForSamePost() throws Exception {
        Task task = new Task();
        task.taskId = "task-concurrent";
        task.keyword = "roadster";
        Post post = new Post();
        post.postRef = "P001";
        post.postId = "abc";
        post.title = "title";
        post.collectionStatus = "SUCCESS";
        when(repository.find(task.taskId)).thenReturn(Optional.of(task));
        when(repository.post(task.taskId, "P001")).thenReturn(Optional.of(post));

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(modelService.generateFiveW1H(anyString(), anyString(), anyMap())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return Map.of("what", Map.of("text", "ok", "evidence", List.of("P001")));
        });

        TaskManager manager = new TaskManager(repository, orchestrator, events, modelService, new AssistantProperties());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<AnalysisStatus> first = pool.submit(() -> manager.startFiveW1H(task.taskId, "P001"));
            Future<AnalysisStatus> second = pool.submit(() -> manager.startFiveW1H(task.taskId, "P001"));
            assertThat(first.get(2, TimeUnit.SECONDS).status).isEqualTo("RUNNING");
            assertThat(second.get(2, TimeUnit.SECONDS).status).isEqualTo("RUNNING");
            release.countDown();
        }

        AtomicReference<AnalysisStatus> status = new AtomicReference<>();
        for (int i = 0; i < 100; i++) {
            status.set(manager.analysisStatus(task.taskId, "P001"));
            if ("COMPLETED".equals(status.get().status) || "FAILED".equals(status.get().status)) break;
            Thread.sleep(20);
        }
        assertThat(status.get().status).isEqualTo("COMPLETED");
        assertThat(calls.get()).isEqualTo(1);
    }
}
