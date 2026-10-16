package com.xhs.assistant.collector;

public class NoteUnavailableException extends RuntimeException {
    private final String postId;
    private final String url;

    public NoteUnavailableException(String postId, String url, String reason) {
        super(reason);
        this.postId = postId;
        this.url = url;
    }

    public String getPostId() { return postId; }
    public String getUrl() { return url; }
}
