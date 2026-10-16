package com.xhs.assistant.domain;

import java.time.OffsetDateTime;

public class TaskEvent {
    public long seq;
    public String type;
    public String message;
    public Object data;
    public OffsetDateTime timestamp;

    public TaskEvent() {}
    public TaskEvent(long seq, String type, String message, Object data, OffsetDateTime timestamp) {
        this.seq = seq; this.type = type; this.message = message;
        this.data = data; this.timestamp = timestamp;
    }
}
