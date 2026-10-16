package com.xhs.assistant.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhs.assistant.domain.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRoundTripFencedJsonMarkdown() {
        MarkdownStore store = new MarkdownStore(new ObjectMapper());
        Post post = new Post();
        post.postRef = "P001";
        post.postId = "abc";
        post.title = "示例";
        post.body = "正文";
        Path file = tempDir.resolve("P001.md");
        store.writeJson(file, "P001", Map.of("type", "post-snapshot"), post);

        Post loaded = store.readJson(file, Post.class);
        assertThat(loaded.postRef).isEqualTo("P001");
        assertThat(loaded.postId).isEqualTo("abc");
        assertThat(loaded.title).isEqualTo("示例");
        assertThat(loaded.body).isEqualTo("正文");
    }
}
