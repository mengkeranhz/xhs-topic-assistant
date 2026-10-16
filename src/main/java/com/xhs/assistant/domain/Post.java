package com.xhs.assistant.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Post {
    public String postRef;
    public String postId;
    public String url;
    public String xsecToken;
    public String title;
    public String author;
    public String publishedAt;
    public OffsetDateTime collectedAt;
    public MetricValue likes = MetricValue.missing();
    public MetricValue collections = MetricValue.missing();
    public MetricValue comments = MetricValue.missing();
    public String body = "";
    public List<ImageText> imageText = new ArrayList<>();
    public boolean videoProcessed = false;
    public List<String> coverage = new ArrayList<>();
    public String coverageNote = "";
    public String relevance = "MAYBE";
    public Double relevanceScore;
    public String relevanceReason = "";
    public String collectionStatus = "PENDING";
    public Map<String, Object> failure;
    public String contentHash = "";
    public boolean possibleDuplicateTitle = false;
    public Map<String, Object> fiveW1H;
    public int attempts;

    public String evidenceText() {
        StringBuilder b = new StringBuilder();
        if (title != null) b.append(title).append('\n');
        if (body != null) b.append(body).append('\n');
        for (ImageText t : imageText) if (t != null && t.text != null) b.append(t.text).append('\n');
        return b.toString();
    }
}
