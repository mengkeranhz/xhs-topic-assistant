package com.xhs.assistant.collector;

import com.xhs.assistant.domain.MetricValue;

import java.util.Locale;
public final class CountParser {
    private CountParser() {}

    public static MetricValue parse(String raw) {
        if (raw == null) return MetricValue.missing();
        String s = raw.replace(",", "").replace("，", "").trim();
        if (s.isBlank() || "-".equals(s)) return MetricValue.missing();
        String normalized = s.toLowerCase(Locale.ROOT);
        try {
            double n;
            String precision = "PAGE_DISPLAY";
            if (normalized.endsWith("万+")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-2)) * 10000; precision = "APPROXIMATE"; }
            else if (normalized.endsWith("万")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-1)) * 10000; precision = "APPROXIMATE"; }
            else if (normalized.endsWith("亿+")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-2)) * 100000000; precision = "APPROXIMATE"; }
            else if (normalized.endsWith("亿")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-1)) * 100000000; precision = "APPROXIMATE"; }
            else if (normalized.endsWith("+")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-1)); precision = "APPROXIMATE"; }
            else if (normalized.contains("w")) { n = Double.parseDouble(normalized.substring(0, normalized.length()-1)) * 10000; precision = "APPROXIMATE"; }
            else { n = Double.parseDouble(normalized); }
            return new MetricValue(raw, (long)Math.ceil(n), precision, false);
        } catch (Exception e) { return MetricValue.missing(); }
    }
}
