package com.xhs.assistant.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class QuestionRecord {
    public String questionId;
    public String taskId;
    public String postRef;
    public String question;
    public String status = "QUEUED";
    public String answer = "";
    public List<String> citations = new ArrayList<>();
    public String failure;
    public OffsetDateTime createdAt;
    public OffsetDateTime finishedAt;
}
