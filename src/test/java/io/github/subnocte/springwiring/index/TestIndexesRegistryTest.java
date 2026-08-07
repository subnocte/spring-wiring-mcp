package io.github.subnocte.springwiring.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TestIndexesRegistry} is the test-wiring counterpart of {@link CodeIndexesRegistry}:
 * same per-root caching and LRU eviction, but built lazily (a session that never calls a
 * test-wiring tool never pays for parsing every test file at startup) — unlike the
 * production registry, whose default root is built eagerly at construction.
 */
class TestIndexesRegistryTest {

    @TempDir
    Path defaultRoot;

    @BeforeEach
    void writeDefaultTestSource() throws IOException {
        writeTestClass(defaultRoot, "Default");
    }

    private static void writeTestClass(Path root, String name) throws IOException {
        Path file = root.resolve("src/test/java/com/example/" + name + "Test.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                class %sTest {
                }
                """.formatted(name));
    }

    private static TestIndexesRegistry.Success asSuccess(TestIndexesRegistry.Lookup lookup) {
        assertThat(lookup).isInstanceOf(TestIndexesRegistry.Success.class);
        return (TestIndexesRegistry.Success) lookup;
    }

    private static TestIndexesRegistry.Failure asFailure(TestIndexesRegistry.Lookup lookup) {
        assertThat(lookup).isInstanceOf(TestIndexesRegistry.Failure.class);
        return (TestIndexesRegistry.Failure) lookup;
    }

    @Test
    void constructorDoesNotBuildTheDefaultRootEagerly(@TempDir Path parent) {
        // A missing default root must not fail the constructor: unlike CodeIndexesRegistry
        // (eager, fails fast), the test-index registry only builds on first use (lazy).
        Path missingRoot = parent.resolve("does-not-exist-yet");

        TestIndexesRegistry registry = new TestIndexesRegistry(missingRoot, 8);

        assertThat(registry.defaultRoot()).isEqualTo(missingRoot.toAbsolutePath().normalize());
    }

    @Test
    void defaultRootIsIndexedOnFirstAccess() {
        TestIndexesRegistry registry = new TestIndexesRegistry(defaultRoot, 8);

        TestIndexes indexes = asSuccess(registry.forRoot(defaultRoot)).indexes();

        assertThat(indexes.current().findByName("DefaultTest")).hasSize(1);
    }

    @Test
    void differentRootsProduceIndependentIndexes(@TempDir Path otherRoot) throws IOException {
        writeTestClass(otherRoot, "Other");
        TestIndexesRegistry registry = new TestIndexesRegistry(defaultRoot, 8);

        TestIndexes defaultIndexes = asSuccess(registry.forRoot(defaultRoot)).indexes();
        TestIndexes otherIndexes = asSuccess(registry.forRoot(otherRoot)).indexes();

        assertThat(defaultIndexes).isNotSameAs(otherIndexes);
        assertThat(defaultIndexes.current().findByName("OtherTest")).isEmpty();
        assertThat(otherIndexes.current().findByName("OtherTest")).hasSize(1);
    }

    @Test
    void sameRootReturnsTheCachedIndexesInstance() {
        TestIndexesRegistry registry = new TestIndexesRegistry(defaultRoot, 8);

        TestIndexes first = asSuccess(registry.forRoot(defaultRoot)).indexes();
        TestIndexes second = asSuccess(registry.forRoot(defaultRoot)).indexes();

        assertThat(second).isSameAs(first);
    }

    @Test
    void nonExistentRootIsReportedNotThrown(@TempDir Path parent) {
        TestIndexesRegistry registry = new TestIndexesRegistry(defaultRoot, 8);
        Path missing = parent.resolve("does-not-exist");

        TestIndexesRegistry.Failure failure = asFailure(registry.forRoot(missing));

        assertThat(failure.reason()).contains(missing.toString());
    }

    @Test
    void exceedingMaxRootsEvictsLeastRecentlyUsedButNeverTheDefault(@TempDir Path base) throws IOException {
        TestIndexesRegistry registry = new TestIndexesRegistry(defaultRoot, 2);
        Path first = Files.createDirectory(base.resolve("first"));
        Path second = Files.createDirectory(base.resolve("second"));
        writeTestClass(first, "First");
        writeTestClass(second, "Second");

        registry.forRoot(defaultRoot); // materialize the (lazy) default first
        registry.forRoot(first);
        TestIndexesRegistry.Success evictingLookup = asSuccess(registry.forRoot(second));

        assertThat(evictingLookup.notices()).isNotEmpty();
        assertThat(registry.knownRoots())
                .contains(defaultRoot.toAbsolutePath().normalize())
                .contains(second.toAbsolutePath().normalize())
                .doesNotContain(first.toAbsolutePath().normalize());
    }
}
