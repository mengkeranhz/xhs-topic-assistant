package com.xhs.assistant.pipeline;

import com.xhs.assistant.collector.XhsCollector;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOcrRecoveryTest {
    @Mock private TaskRepository repository;
    @Mock private MarkdownStore markdownStore;
    @Mock private XhsCollector collector;
    @Mock private ModelService modelService;
    @Mock private ReportAgent reportAgent;
    @Mock private IncrementalService incrementalService;
    @Mock private TaskEventPublisher events;

    @TempDir
    Path tempDir;

    @Test
    void shouldRecoverPendingOcrFromPersistedImageAndNeverDropRecord() throws Exception {
        Task task = new Task();
        task.taskId = "ocr-recovery";
        Post post = new Post();
        post.postRef = "P001";
        post.postId = "abc";
        post.title = "title";
        post.body = "body";
        post.collectionStatus = "SUCCESS";
        ImageText pending = new ImageText();
        pending.imageIndex = 1;
        pending.sourceUrl = "https://www.xiaohongshu.com/explore/abc";
        pending.status = "PENDING";
        post.imageText.add(pending);

        byte[] png = new byte[]{1, 2, 3};
        Path imageDir = tempDir.resolve("attachments/images");
        java.nio.file.Files.createDirectories(imageDir);
        java.nio.file.Files.write(imageDir.resolve("P001-001.png"), png);
        when(repository.taskDir(task.taskId)).thenReturn(tempDir);
        when(modelService.extractImageText(eq(task.taskId), eq("P001-1"), any()))
                .thenReturn(new ModelService.ImageTextResult("visible text", "HIGH"));

        TaskOrchestrator orchestrator = new TaskOrchestrator(repository, markdownStore, collector,
                modelService, reportAgent, incrementalService, events, new UsageTracker(),
                new AssistantProperties());
        Method method = TaskOrchestrator.class.getDeclaredMethod("extractImages", Task.class,
                RunContext.class, List.class, Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Post> result = (List<Post>) method.invoke(orchestrator, task, new RunContext(task.taskId),
                List.of(post), Map.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imageText).hasSize(1);
        assertThat(result.get(0).imageText.get(0).status).isEqualTo("SUCCESS");
        assertThat(result.get(0).imageText.get(0).text).isEqualTo("visible text");
        verify(modelService).extractImageText(eq(task.taskId), eq("P001-1"), any());
    }
}
