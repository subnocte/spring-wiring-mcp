package io.github.subnocte.springwiring.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index holder must never answer from sources that no longer match the disk:
 * any add, edit, or delete under the root is picked up on the next access.
 */
class CodeIndexesTest {

    @TempDir
    Path root;

    private Path controller;

    private static final String CONTROLLER_V1 = """
            package com.example.live;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class LiveController {

                @GetMapping("/alpha")
                public String alpha() {
                    return "a";
                }
            }
            """;

    private static final String CONTROLLER_V2 = """
            package com.example.live;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class LiveController {

                @GetMapping("/alpha")
                public String alpha() {
                    return "a";
                }

                @GetMapping("/beta")
                public String beta() {
                    return "b";
                }
            }
            """;

    @BeforeEach
    void writeInitialSource() throws IOException {
        controller = root.resolve("src/main/java/com/example/live/LiveController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, CONTROLLER_V1);
    }

    /** mtime granularity can be a full second; force a visible difference. */
    private static void touchLater(Path file) throws IOException {
        Files.setLastModifiedTime(file,
                FileTime.from(Files.getLastModifiedTime(file).toInstant().plusSeconds(10)));
    }

    @Test
    void unchangedSourcesReturnTheSameSnapshotInstance() {
        CodeIndexes indexes = CodeIndexes.forRoot(root);

        CodeIndexes.Snapshot first = indexes.current();
        CodeIndexes.Snapshot second = indexes.current();

        assertThat(second).isSameAs(first);
    }

    @Test
    void editedFileIsReindexedOnNextAccess() throws IOException {
        CodeIndexes indexes = CodeIndexes.forRoot(root);
        assertThat(indexes.current().endpointIndex().resolve("GET", "/beta")).isEmpty();

        Files.writeString(controller, CONTROLLER_V2);
        touchLater(controller);

        assertThat(indexes.current().endpointIndex().resolve("GET", "/beta")).isPresent();
    }

    @Test
    void addedFileIsReindexedOnNextAccess() throws IOException {
        CodeIndexes indexes = CodeIndexes.forRoot(root);
        assertThat(indexes.current().beanIndex().findByName("LiveService")).isEmpty();

        Path service = root.resolve("src/main/java/com/example/live/LiveService.java");
        Files.writeString(service, """
                package com.example.live;

                import org.springframework.stereotype.Service;

                @Service
                public class LiveService {
                }
                """);

        assertThat(indexes.current().beanIndex().findByName("LiveService")).hasSize(1);
    }

    @Test
    void deletedFileIsReindexedOnNextAccess() throws IOException {
        CodeIndexes indexes = CodeIndexes.forRoot(root);
        assertThat(indexes.current().endpointIndex().resolve("GET", "/alpha")).isPresent();

        Files.delete(controller);

        assertThat(indexes.current().endpointIndex().resolve("GET", "/alpha")).isEmpty();
    }
}
