package com.xhs.assistant.pipeline;

import com.xhs.assistant.domain.TaskEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseEventHub {
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String taskId, SseEmitter emitter) {
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));
    }

    public void send(String taskId, TaskEvent event) {
        for (SseEmitter emitter : subscribers.getOrDefault(taskId, List.of())) {
            try {
                emitter.send(SseEmitter.event().name(event.type).data(event));
            } catch (Exception e) {
                remove(taskId, emitter);
            }
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(taskId);
        if (list != null) list.remove(emitter);
    }
}
