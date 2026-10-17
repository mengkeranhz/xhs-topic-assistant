package com.xhs.assistant.pipeline;

import com.xhs.assistant.collector.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskOrchestratorRecoveryTest {
    @Mock private TaskRepository repository;
    @Mock private MarkdownStore markdownStore;
    @Mock private XhsCollector collector;
    @Mock private ModelService modelService;
    @Mock private ReportAgent reportAgent;
    @Mock private IncrementalService incrementalService;
    @Mock private TaskEventPublisher events;

    private TaskOrchestrator orchestrator() {
        return new TaskOrchestrator(repository, markdownStore, collector, modelService, reportAgent,
                incrementalService, events, new UsageTracker(), new AssistantProperties());
    }

    private Post post(String ref, String postId, String status) {
        Post post = new Post();
        post.postRef = ref;
        post.postId = postId;
        post.url = "https://www.xiaohongshu.com/explore/" + postId + "?xsec_token=abc&xsec_source=pc_search";
        post.title = "title-" + ref;
        post.body = "SUCCESS".equals(status) ? "body" : "";
        post.collectionStatus = status;
        return post;
    }

    @Test
    void shouldKeepSelectedSnapshotSizeWhenRetryingPartialFailure() {
        Task task = new Task();
        task.taskId = "retry-task";
        task.keyword = "roadster";
        task.orchestration.checkpoint.selectedPostsSaved = true;
        task.orchestration.checkpoint.selectedPostRefs = List.of("P001", "P002", "P003");

        Post selected1 = post("P001", "id1", "CANDIDATE");
        Post selected2 = post("P002", "id2", "CANDIDATE");
        Post selected3 = post("P003", "id3", "CANDIDATE");
        Post executedSuccess = post("P001", "id1", "SUCCESS");
        Post executedFailure = post("P002", "id2", "FAILED");

        when(repository.selected(task.taskId)).thenReturn(Optional.of(List.of(selected1, selected2, selected3)));
        when(repository.posts(task.taskId)).thenReturn(List.of(executedSuccess, executedFailure));

        Post recovered2 = post("P002", "id2", "SUCCESS");
        Post recovered3 = post("P003", "id3", "SUCCESS");
        when(collector.collectDetail(any())).thenReturn(
                new XhsCollector.DetailResult(recovered2, List.of()),
                new XhsCollector.DetailResult(recovered3, List.of()));

        ReportRecord report = new ReportRecord();
        report.failedChecks = List.of();
        report.finalAnswerGate = "PASSED";
        when(reportAgent.generate(eq(task), anyList(), any())).thenReturn(report);
        when(repository.metrics(task.taskId)).thenReturn(Optional.empty());

        orchestrator().run(task, new RunContext(task.taskId));

        assertThat(task.collection.selectedCount).isEqualTo(3);
        assertThat(task.collection.successfulDetailCount).isEqualTo(3);
        assertThat(task.collection.failedDetailCount).isZero();
        assertThat(task.status).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void shouldCompleteEmptySearchWithoutClaimingAllDetailsFailed() {
        Task task = new Task();
        task.taskId = "empty-task";
        task.keyword = "empty-keyword";
        when(repository.taskDir(task.taskId)).thenReturn(java.nio.file.Path.of("/tmp", task.taskId));
        when(collector.collectCandidates(task.keyword)).thenReturn(List.of());
        when(modelService.classifyRelevance(eq(task.taskId), eq(task.keyword), anyList())).thenReturn(List.of());
        when(repository.metrics(task.taskId)).thenReturn(Optional.empty());

        orchestrator().run(task, new RunContext(task.taskId));

        assertThat(task.status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.failureCode).isNull();
        assertThat(task.collection.selectedCount).isZero();

        ArgumentCaptor<ReportRecord> captor = ArgumentCaptor.forClass(ReportRecord.class);
        verify(repository).saveReport(eq(task.taskId), captor.capture());
        assertThat(captor.getValue().reportMarkdown).contains("本次搜索范围内没有相关帖子");
        assertThat(captor.getValue().finalAnswerGate).isEqualTo("PASSED");
    }
}
