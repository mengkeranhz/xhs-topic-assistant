package com.xhs.assistant.domain;

public enum TaskStatus {
    CREATED, QUEUED, WAITING_BROWSER, SEARCHING, CLASSIFYING, RANKING, COLLECTING_DETAILS,
    EXTRACTING_IMAGES, GENERATING_REPORT, WAITING_USER, CANCELED, INTERRUPTED,
    PARTIALLY_COMPLETED, FAILED, COMPLETED;

    public boolean isTerminal() {
        return this == CANCELED || this == FAILED || this == COMPLETED || this == PARTIALLY_COMPLETED;
    }

    public boolean isRunnable() {
        return !isTerminal() && this != WAITING_USER;
    }
}
