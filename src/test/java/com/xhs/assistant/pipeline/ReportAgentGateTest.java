package com.xhs.assistant.pipeline;

import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import com.xhs.assistant.llm.LlmTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportAgentGateTest {
    @Mock private ModelService modelService;

    private Task task() {
        Task task = new Task();
        task.taskId = "t1";
        task.keyword = "Roadster";
        task.collection.distinctPostCount = 1;
        task.collection.selectedCount = 1;
        task.collection.successfulDetailCount = 1;
        return task;
    }

    private Post post() {
        Post post = new Post();
        post.postRef = "P001";
        post.postId = "abc";
        post.title = "特斯拉 Roadster 发布";
        post.body = "帖子讨论 Roadster 的发布时间和性能。";
        post.collectionStatus = "SUCCESS";
        return post;
    }

    private String report(String conclusion) {
        return """
                # 关键词研究报告

                ## 1. 范围与限制
                本次搜索范围内。图片OCR成功0张，失败0张，记录0张；视频音画未处理。

                ## 2. 主要讨论主题
                %s

                ## 3. 反复出现的观点
                - 相关材料讨论 Roadster 性能 [P001]

                ## 4. 明显分歧
                - 无明显分歧。

                ## 5. 场景、体验、问题与方法
                - 材料讨论发布场景 [P001]

                ## 6. 证据类型判断
                - 属于帖子内容转述 [P001]

                ## 7. 本批材料无法回答的问题
                - 其他问题无法回答。

                ## 8. 热门帖子列表
                - [P001] 特斯拉 Roadster 发布
                """.formatted(conclusion);
    }

    @Test
    void shouldRejectValidCitationWhenClaimHasNoEvidenceSupport() {
        Task task = task();
        Post post = post();
        when(modelService.planReport(anyString(), anyString(), any())).thenReturn(List.of(Map.of("query", "Roadster")));
        AtomicReference<java.util.function.Consumer<String>> handler = new AtomicReference<>();
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(3));
            handler.get().accept(report("作者认为售价是50万元 [P001]"));
            return null;
        }).when(modelService).synthesizeReport(anyString(), anyString(), anyString(), any());

        ReportRecord record = new ReportAgent(modelService, new UsageTracker(), new AssistantProperties())
                .generate(task, List.of(post), token -> {});
        assertThat(record.failedChecks).anyMatch(x -> x.contains("结论缺乏原文证据支持") || x.contains("结论数值无证据支持"));
        assertThat(record.finalAnswerGate).isNotEqualTo("PASSED");
    }

    @Test
    void shouldNotPromotePartialStreamDraftToCanonicalReportAfterFailure() {
        Task task = task();
        Post post = post();
        AssistantProperties properties = new AssistantProperties();
        properties.getModel().setAllowOfflineFallback(true);
        when(modelService.planReport(anyString(), anyString(), any())).thenReturn(List.of(Map.of("query", "Roadster")));
        doAnswer(invocation -> {
            java.util.function.Consumer<String> handler = invocation.getArgument(3);
            handler.accept("未完成的断言");
            throw new IllegalStateException("stream failed");
        }).when(modelService).synthesizeReport(anyString(), anyString(), anyString(), any());

        ReportRecord record = new ReportAgent(modelService, new UsageTracker(), properties)
                .generate(task, List.of(post), token -> {});
        assertThat(record.reportMarkdown).doesNotContain("未完成的断言");
        assertThat(record.reportMarkdown).contains("离线降级模式");
        assertThat(record.failedChecks).contains("MODEL_SYNTHESIS_DEGRADED");
        assertThat(record.finalAnswerGate).isNotEqualTo("PASSED");
    }
}
