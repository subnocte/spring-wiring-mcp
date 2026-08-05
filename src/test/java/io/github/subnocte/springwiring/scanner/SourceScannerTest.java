package io.github.subnocte.springwiring.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceScannerTest {

    @TempDir
    Path root;

    private Path touch(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "class X {}");
    }

    @Test
    void collectsJavaFilesAndIgnoresOtherFiles() throws IOException {
        Path kept = touch("src/main/java/com/example/A.java");
        touch("src/main/resources/application.properties");
        touch("README.md");

        assertThat(SourceScanner.scan(root)).containsExactly(kept.toAbsolutePath());
    }

    @Test
    void skipsBuildOutputAndDependencyDirectories() throws IOException {
        Path kept = touch("src/main/java/com/example/A.java");
        // copies and generated sources that would duplicate or fake endpoints
        touch("build/generated/com/example/Gen.java");
        touch("target/classes/com/example/Copied.java");
        touch("out/production/com/example/Copied.java");
        touch("bin/com/example/Copied.java");
        touch("frontend/node_modules/some-pkg/Embedded.java");

        assertThat(SourceScanner.scan(root)).containsExactly(kept.toAbsolutePath());
    }

    @Test
    void skipsHiddenDirectories() throws IOException {
        Path kept = touch("src/main/java/com/example/A.java");
        touch(".git/objects/Fake.java");
        touch(".gradle/caches/Fake.java");
        touch(".idea/scratches/Fake.java");

        assertThat(SourceScanner.scan(root)).containsExactly(kept.toAbsolutePath());
    }

    @Test
    void skipsTestSourceSets() throws IOException {
        Path kept = touch("src/main/java/com/example/A.java");
        touch("src/test/java/com/example/ATest.java");
        touch("src/integrationTest/java/com/example/AIT.java");
        touch("src/testFixtures/java/com/example/Fixture.java");

        assertThat(SourceScanner.scan(root)).containsExactly(kept.toAbsolutePath());
    }

    @Test
    void doesNotSkipTestLikeNamesOutsideSrc() throws IOException {
        // only "src/<something containing test>" is a test source set;
        // an application package named "test" must still be scanned
        Path kept = touch("src/main/java/com/example/test/A.java");

        assertThat(SourceScanner.scan(root)).containsExactly(kept.toAbsolutePath());
    }

    @Test
    void rootItselfIsNeverSkippedEvenWithExcludedName(@TempDir Path parent) throws IOException {
        Path buildNamedRoot = Files.createDirectory(parent.resolve("build"));
        Path file = buildNamedRoot.resolve("A.java");
        Files.writeString(file, "class A {}");

        assertThat(SourceScanner.scan(buildNamedRoot)).containsExactly(file.toAbsolutePath());
    }

    @Test
    void rejectsNonDirectoryRoot() {
        assertThatThrownBy(() -> SourceScanner.scan(root.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
