package com.xhs.assistant.domain;

import java.util.ArrayList;
import java.util.List;

public class ImageText {
    public int imageIndex;
    public String sourceUrl;
    public String status = "PENDING";
    public String text = "";
    public String confidence = "UNKNOWN";
    public List<String> notes = new ArrayList<>();
    public String failureReason;
}
