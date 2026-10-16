package com.xhs.assistant.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarkdownStore {
    private final ObjectMapper objectMapper;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public MarkdownStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.findAndRegisterModules();
    }

    public Object lockFor(Path path) {
        return locks.computeIfAbsent(path.toAbsolutePath().normalize().toString(), k -> new Object());
    }

    public synchronized void writeJson(Path path, String title, Map<String, Object> frontMatter, Object value) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            StringBuilder md = new StringBuilder("---\n");
            for (Map.Entry<String, Object> e : frontMatter.entrySet()) {
                md.append(e.getKey()).append(": ").append(String.valueOf(e.getValue()).replace("\n", " ")).append('\n');
            }
            md.append("---\n\n# ").append(title).append("\n\n```json\n")
              .append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
              .append("\n```\n");
            Path tmp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString() + ".", ".tmp");
            Files.writeString(tmp, md.toString(), StandardCharsets.UTF_8);
            AtomicMoveSupport.move(tmp, absolute);
        } catch (Exception e) {
            throw new StorageException("Cannot write " + path, e);
        }
    }

    public <T> T readJson(Path path, Class<T> type) {
        try {
            if (!Files.exists(path)) return null;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int start = content.indexOf("```json");
            int end = content.indexOf("\n```", start);
            if (start < 0 || end < 0) throw new StorageException("No JSON block in " + path, null);
            String json = content.substring(start + 7, end);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new StorageException("Cannot read " + path, e);
        }
    }

    public void appendJsonLine(Path path, Map<String, Object> frontMatter, Object value) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            synchronized (lockFor(absolute)) {
                if (!Files.exists(absolute)) {
                    StringBuilder head = new StringBuilder("---\n");
                    frontMatter.forEach((k, v) -> head.append(k).append(": ").append(v).append('\n'));
                    head.append("---\n\n# Events\n\n");
                    Files.writeString(absolute, head.toString(), StandardCharsets.UTF_8);
                }
                Files.writeString(absolute, "- " + objectMapper.writeValueAsString(value) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            throw new StorageException("Cannot append " + path, e);
        }
    }

    public void writeBytes(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            Path tmp = Files.createTempFile(path.toAbsolutePath().normalize().getParent(), "asset-", ".tmp");
            Files.write(tmp, bytes);
            AtomicMoveSupport.move(tmp, path.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new StorageException("Cannot write asset " + path, e);
        }
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class AtomicMoveSupport {
        private static void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
