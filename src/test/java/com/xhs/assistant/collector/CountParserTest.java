package com.xhs.assistant.collector;

import com.xhs.assistant.domain.MetricValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountParserTest {
    @Test
    void shouldNormalizeChineseApproximateCounts() {
        MetricValue value = CountParser.parse("1.2万");
        assertThat(value.raw).isEqualTo("1.2万");
        assertThat(value.value).isEqualTo(12000L);
        assertThat(value.precision).isEqualTo("APPROXIMATE");
        assertThat(value.missing).isFalse();
    }

    @Test
    void shouldKeepMissingValueMissing() {
        MetricValue value = CountParser.parse(null);
        assertThat(value.value).isNull();
        assertThat(value.missing).isTrue();
        assertThat(value.precision).isEqualTo("MISSING");
    }
}
