package com.xhs.assistant.collector;

import com.microsoft.playwright.Page;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XhsCollectorMetricPriorityTest {
    @Mock private Page page;

    @Test
    void shouldPreferStructuredLikeCountOverNumberInTitleText() throws Exception {
        Map<String, Object> state = Map.of(
                "_id", "abc",
                "note_card", Map.of(
                        "interact_info", Map.of(
                                "liked_count", "12000",
                                "collected_count", "300",
                                "comment_count", "45"
                        )
                )
        );
        Map<String, Object> card = Map.of(
                "href", "https://www.xiaohongshu.com/explore/abc?xsec_token=token&xsec_source=pc_search",
                "attr", "https://www.xiaohongshu.com/explore/abc?xsec_token=token",
                "dataHref", "",
                "html", "",
                "text", "1 个值得看的 Roadster 视频\n"
        );
        when(page.evaluate(anyString())).thenReturn(state, List.of(card));

        XhsCollector collector = new XhsCollector(new AssistantProperties());
        Method method = XhsCollector.class.getDeclaredMethod("extractCards", Page.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Post> result = (Map<String, Post>) method.invoke(collector, page, "Roadster");

        assertThat(result).hasSize(1);
        assertThat(result.get("abc").likes.value).isEqualTo(12000L);
        assertThat(result.get("abc").collections.value).isEqualTo(300L);
        assertThat(result.get("abc").comments.value).isEqualTo(45L);
    }
}
