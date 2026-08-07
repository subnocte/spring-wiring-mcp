package io.github.subnocte.springwiring.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CodeIndexesRegistry} lets tools analyze directories other than the server's
 * startup {@code code.root}: every root gets its own lazily-built, cached {@link CodeIndexes},
 * bad input is reported rather than thrown, and an unbounded number of ad hoc roots must
 * not leak memory forever (LRU eviction, default root always kept).
 */
class CodeIndexesRegistryTest {

    @TempDir
    Path defaultRoot;

    @BeforeEach
    void writeDefaultSource() throws IOException {
        writeController(defaultRoot, "Default", "/default-alpha");
    }

    private static void writeController(Path root, String name, String path) throws IOException {
        Path file = root.resolve("src/main/java/com/example/" + name + "Controller.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class %sController {

                    @GetMapping("%s")
                    public String hit() {
                        return "x";
                    }
                }
                """.formatted(name, path));
    }

    private static CodeIndexesRegistry.Success asSuccess(CodeIndexesRegistry.Lookup lookup) {
        assertThat(lookup).isInstanceOf(CodeIndexesRegistry.Success.class);
        return (CodeIndexesRegistry.Success) lookup;
    }

    private static CodeIndexesRegistry.Failure asFailure(CodeIndexesRegistry.Lookup lookup) {
        assertThat(lookup).isInstanceOf(CodeIndexesRegistry.Failure.class);
        return (CodeIndexesRegistry.Failure) lookup;
    }

    @Test
    void defaultRootIsIndexedEagerlyAtConstruction() {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);

        CodeIndexes indexes = asSuccess(registry.forRoot(defaultRoot)).indexes();

        assertThat(indexes.current().endpointIndex().resolve("GET", "/default-alpha")).isPresent();
    }

    @Test
    void differentRootsProduceIndependentIndexes(@TempDir Path otherRoot) throws IOException {
        writeController(otherRoot, "Other", "/other-alpha");
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);

        CodeIndexes defaultIndexes = asSuccess(registry.forRoot(defaultRoot)).indexes();
        CodeIndexes otherIndexes = asSuccess(registry.forRoot(otherRoot)).indexes();

        assertThat(defaultIndexes).isNotSameAs(otherIndexes);
        assertThat(defaultIndexes.current().endpointIndex().resolve("GET", "/other-alpha")).isEmpty();
        assertThat(otherIndexes.current().endpointIndex().resolve("GET", "/other-alpha")).isPresent();
    }

    @Test
    void sameRootReturnsTheCachedIndexesInstance() {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);

        CodeIndexes first = asSuccess(registry.forRoot(defaultRoot)).indexes();
        CodeIndexes second = asSuccess(registry.forRoot(defaultRoot)).indexes();

        assertThat(second).isSameAs(first);
    }

    @Test
    void nonExistentRootIsReportedNotThrown(@TempDir Path parent) {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);
        Path missing = parent.resolve("does-not-exist");

        CodeIndexesRegistry.Failure failure = asFailure(registry.forRoot(missing));

        assertThat(failure.reason()).contains(missing.toString());
    }

    @Test
    void nonDirectoryRootIsReportedNotThrown() throws IOException {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);
        Path file = defaultRoot.resolve("not-a-dir.txt");
        Files.writeString(file, "x");

        CodeIndexesRegistry.Failure failure = asFailure(registry.forRoot(file));

        assertThat(failure.reason()).contains(file.toString());
    }

    @Test
    void exceedingMaxRootsEvictsLeastRecentlyUsedButNeverTheDefault(@TempDir Path base) throws IOException {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot, 2);
        Path first = Files.createDirectory(base.resolve("first"));
        Path second = Files.createDirectory(base.resolve("second"));
        writeController(first, "First", "/first-alpha");
        writeController(second, "Second", "/second-alpha");

        registry.forRoot(first);
        CodeIndexesRegistry.Success evictingLookup = asSuccess(registry.forRoot(second));

        assertThat(evictingLookup.notices()).isNotEmpty();
        assertThat(evictingLookup.notices().get(0)).contains(first.toString());
        assertThat(registry.knownRoots())
                .contains(defaultRoot.toAbsolutePath().normalize())
                .contains(second.toAbsolutePath().normalize())
                .doesNotContain(first.toAbsolutePath().normalize());
    }

    @Test
    void reAccessingARootDoesNotCountAsGrowthForEviction(@TempDir Path base) throws IOException {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot, 2);
        Path first = Files.createDirectory(base.resolve("first"));
        writeController(first, "First", "/first-alpha");

        registry.forRoot(first);
        CodeIndexesRegistry.Success repeated = asSuccess(registry.forRoot(first));

        assertThat(repeated.notices()).isEmpty();
        assertThat(registry.knownRoots())
                .contains(defaultRoot.toAbsolutePath().normalize())
                .contains(first.toAbsolutePath().normalize());
    }
}
