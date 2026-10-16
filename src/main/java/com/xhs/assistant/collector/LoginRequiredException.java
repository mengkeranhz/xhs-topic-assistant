package com.xhs.assistant.collector;

public class LoginRequiredException extends RuntimeException {
    public LoginRequiredException(String message) { super(message); }
}
