package com.xhs.assistant.collector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XhsCollectorDetailUrlTest {
    @Test
    void shouldBuildSignedDetailUrlWithSearchSource() {
        String token = "ABB9XNU7V3iEjkqf1Z18oQD271aP-rFnNffXiGg-9FvWM=";
        String url = XhsCollector.detailUrl("6aa7637d000000002502fdbd", token,
                "https://www.xiaohongshu.com/explore/6aa7637d000000002502fdbd");
        assertThat(url)
                .startsWith("https://www.xiaohongshu.com/explore/6aa7637d000000002502fdbd?")
                .contains("xsec_token=")
                .contains("xsec_source=pc_search")
                .contains("source=web_explore_feed");
        assertThat(XhsCollector.postId(url)).isEqualTo("6aa7637d000000002502fdbd");
    }

    @Test
    void shouldExtractTokenFromSearchResultUrl() {
        String token = XhsCollector.xsecTokenFromUrl(
                "https://www.xiaohongshu.com/explore/6aa7637d?xsec_token=ABB9abc%3D&xsec_source=pc_search");
        assertThat(token).isEqualTo("ABB9abc=");
    }

    @Test
    void shouldDetectBlockedDetailTitle() {
        assertThat(XhsCollector.isBlockedDetailTitle("当前笔记暂时无法浏览")).isTrue();
        assertThat(XhsCollector.isBlockedDetailTitle("特斯拉Roadster")).isFalse();
    }
}
