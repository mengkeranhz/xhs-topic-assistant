package com.xhs.assistant.domain;

public class MetricValue {
    public String raw;
    public Long value;
    public String precision = "MISSING";
    public boolean missing = true;

    public MetricValue() {}
    public MetricValue(String raw, Long value, String precision, boolean missing) {
        this.raw = raw; this.value = value; this.precision = precision; this.missing = missing;
    }
    public static MetricValue missing() { return new MetricValue(null, null, "MISSING", true); }
}
