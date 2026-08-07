package io.github.subnocte.springwiring.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TestIndexes} must never answer from stale test sources, exactly like
 * {@link CodeIndexes} for production sources — but scans {@code src/test} instead of
 * {@code src/main}, so its fingerprint domain is entirely separate: editing a
 * production-only file must never trigger a test-wiring rebuild.
 */
class TestIndexesTest {

    @TempDir
    Path root;

    private Path testClass;

    private static final String TEST_V1 = """
            package com.example.live;

            class LiveTest {
            }
            """;

    @BeforeEach
    void writeInitialSource() throws IOException {
        testClass = root.resolve("src/test/java/com/example/live/LiveTest.java");
        Files.createDirectories(testClass.getParent());
        Files.writeString(testClass, TEST_V1);
    }

    /** mtime granularity can be a full second; force a visible difference. */
    private static void touchLater(Path file) throws IOException {
        Files.setLastModifiedTime(file,
                FileTime.from(Files.getLastModifiedTime(file).toInstant().plusSeconds(10)));
    }

    @Test
    void unchangedSourcesReturnTheSameSnapshotInstance() {
        TestIndexes indexes = TestIndexes.forRoot(root);

        var first = indexes.current();
        var second = indexes.current();

        assertThat(second).isSameAs(first);
    }

    @Test
    void addedTestFileIsReindexedOnNextAccess() throws IOException {
        TestIndexes indexes = TestIndexes.forRoot(root);
        assertThat(indexes.current().findByName("SecondTest")).isEmpty();

        Path second = root.resolve("src/test/java/com/example/live/SecondTest.java");
        Files.writeString(second, """
                package com.example.live;

                class SecondTest {
                }
                """);

        assertThat(indexes.current().findByName("SecondTest")).hasSize(1);
    }

    @Test
    void deletedTestFileIsReindexedOnNextAccess() throws IOException {
        TestIndexes indexes = TestIndexes.forRoot(root);
        assertThat(indexes.current().findByName("LiveTest")).hasSize(1);

        Files.delete(testClass);

        assertThat(indexes.current().findByName("LiveTest")).isEmpty();
    }

    @Test
    void editingAProductionOnlyFileDoesNotTriggerARebuild() throws IOException {
        Path production = root.resolve("src/main/java/com/example/live/LiveService.java");
        Files.createDirectories(production.getParent());
        Files.writeString(production, "package com.example.live; class LiveService { }");
        TestIndexes indexes = TestIndexes.forRoot(root);
        var first = indexes.current();

        Files.writeString(production, "package com.example.live; class LiveService { void x() {} }");
        touchLater(production);

        assertThat(indexes.current()).isSameAs(first);
    }
}
