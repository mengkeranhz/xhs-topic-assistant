package com.xhs.assistant.collector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageInteractionStateTest {
    @Test
    void shouldNotTreatLongTechnicalArticleMentioningCaptchaAsCaptchaPage() {
        String article = "这是一篇讲解验证码识别系统实现原理的技术文章。".repeat(80);
        assertThat(XhsCollector.interactionState(
                "https://www.xiaohongshu.com/explore/abc", "技术文章", article, 0))
                .isEqualTo(XhsCollector.PageInteractionState.NORMAL);
    }

    @Test
    void shouldDetectStrongCaptchaSignals() {
        assertThat(XhsCollector.interactionState(
                "https://www.xiaohongshu.com/captcha", "安全验证", "请完成验证码", 0))
                .isEqualTo(XhsCollector.PageInteractionState.CAPTCHA_REQUIRED);
        assertThat(XhsCollector.interactionState(
                "https://www.xiaohongshu.com/explore/abc", "笔记", "请完成验证码", 1))
                .isEqualTo(XhsCollector.PageInteractionState.CAPTCHA_REQUIRED);
    }

    @Test
    void shouldDetectLoginUrl() {
        assertThat(XhsCollector.interactionState(
                "https://www.xiaohongshu.com/login", "", "登录", 0))
                .isEqualTo(XhsCollector.PageInteractionState.LOGIN_REQUIRED);
    }
}
