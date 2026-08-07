package io.github.subnocte.springwiring.ref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RefMaterializer} is the only place in this project that knows git: it resolves a
 * ref to an immutable SHA in the caller's repository, then materializes that SHA's
 * analyzable sources (as {@link io.github.subnocte.springwiring.scanner.SourceScanner}
 * defines "analyzable") onto disk so {@link io.github.subnocte.springwiring.index.CodeIndexesRegistry}
 * can index it like any other directory.
 *
 * <p>Fixture repos are built fresh per test by copying {@code src/test/resources/sample-project}
 * into a {@code @TempDir} and running real git commands against it (no binary {@code .git}
 * checked into the repository), with the git identity pinned via {@code -c} flags so the
 * test does not depend on the machine's global git config.
 */
class RefMaterializerTest {

    @TempDir
    Path repoDir;

    @TempDir
    Path refsBase;

    private RefMaterializer materializer;

    @BeforeEach
    void createMaterializer() {
        // Must not be a field initializer: JUnit5 injects @TempDir instance fields via a
        // post-processor that runs after the constructor, so refsBase is still null at
        // field-initialization time.
        materializer = new RefMaterializer(refsBase);
    }

    private static void run(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    private static String runCapture(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();
        return output;
    }

    private static void copySampleProject(Path target) throws IOException, URISyntaxException {
        Path source = Path.of(Objects.requireNonNull(
                RefMaterializerTest.class.getResource("/sample-project")).toURI());
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            }
        }
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "commit", "-m", message);
    }

    private static Path initGitRepo(Path repo) throws IOException, InterruptedException, URISyntaxException {
        copySampleProject(repo);
        // a top-level non-.java file: proves extraction filters by more than just "was in git"
        Files.writeString(repo.resolve("README.md"), "not source, must not be extracted");
        run(repo, "git", "init", "-b", "main");
        commit(repo, "initial");
        return repo;
    }

    private static String headSha(Path repo) throws IOException, InterruptedException {
        return runCapture(repo, "git", "rev-parse", "HEAD");
    }

    private static void appendCommit(Path repo, String suffix) throws IOException, InterruptedException {
        Files.writeString(repo.resolve("CHANGE-" + suffix + ".md"), suffix);
        commit(repo, "change " + suffix);
    }

    private static RefMaterializer.Success asSuccess(RefMaterializer.Result result) {
        assertThat(result).isInstanceOf(RefMaterializer.Success.class);
        return (RefMaterializer.Success) result;
    }

    private static RefMaterializer.Failure asFailure(RefMaterializer.Result result) {
        assertThat(result).isInstanceOf(RefMaterializer.Failure.class);
        return (RefMaterializer.Failure) result;
    }

    private static boolean containsFileNamed(Path directory, String fileName) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.anyMatch(p -> p.getFileName() != null && p.getFileName().toString().equals(fileName));
        }
    }

    @Test
    void resolvesRefToShaAndCommitTimestamp() throws Exception {
        initGitRepo(repoDir);

        RefMaterializer.Success success = asSuccess(materializer.materialize(repoDir, "main"));

        assertThat(success.sha()).isEqualTo(headSha(repoDir));
        assertThat(success.sha()).matches("[0-9a-f]{40}");
        assertThat(success.committedAt()).isNotBlank();
    }

    @Test
    void onlyAnalyzableJavaSourcesAreExtracted() throws Exception {
        initGitRepo(repoDir);

        RefMaterializer.Success success = asSuccess(materializer.materialize(repoDir, "main"));
        Path directory = success.directory();

        assertThat(containsFileNamed(directory, "PingController.java")).isTrue();
        assertThat(containsFileNamed(directory, "README.md")).isFalse();
        // excluded by SourceScanner: build output, even though it ends in .java
        assertThat(containsFileNamed(directory, "GhostController.java")).isFalse();
        // test source sets ARE extracted (isSourceFile OR isTestSourceFile): testWiring
        // needs to analyze a ref's test sources, not just its production sources.
        assertThat(containsFileNamed(directory, "TestOnlyController.java")).isTrue();
    }

    @Test
    void secondMaterializationOfSameShaIsACacheHitAndSkipsReExtraction() throws Exception {
        initGitRepo(repoDir);
        RefMaterializer.Success first = asSuccess(materializer.materialize(repoDir, "main"));
        Path markerFile;
        try (Stream<Path> walk = Files.walk(first.directory())) {
            markerFile = walk.filter(p -> p.toString().endsWith(".java")).findFirst().orElseThrow();
        }
        Files.delete(markerFile);

        RefMaterializer.Success second = asSuccess(materializer.materialize(repoDir, "main"));

        assertThat(second.directory()).isEqualTo(first.directory());
        // if materialize() had re-run the archive step, the deleted file would be back
        assertThat(Files.exists(markerFile)).isFalse();
    }

    @Test
    void unknownRefIsReportedNotThrown() throws Exception {
        initGitRepo(repoDir);

        RefMaterializer.Failure failure = asFailure(materializer.materialize(repoDir, "no-such-ref-xyz"));

        assertThat(failure.reason()).contains("no-such-ref-xyz");
    }

    @Test
    void missingGitExecutableIsReportedNotThrown() throws Exception {
        initGitRepo(repoDir);
        RefMaterializer withoutGit = new RefMaterializer(refsBase, RefMaterializer.DEFAULT_MAX_GENERATIONS,
                "git-executable-that-does-not-exist-xyz");

        RefMaterializer.Failure failure = asFailure(withoutGit.materialize(repoDir, "main"));

        assertThat(failure.reason()).containsIgnoringCase("git-executable-that-does-not-exist-xyz");
    }

    @Test
    void exceedingMaxGenerationsDeletesTheOldestMaterializedRef() throws Exception {
        RefMaterializer small = new RefMaterializer(refsBase, 2);
        initGitRepo(repoDir);
        String sha1 = headSha(repoDir);
        Path dir1 = asSuccess(small.materialize(repoDir, sha1)).directory();

        appendCommit(repoDir, "second");
        String sha2 = headSha(repoDir);
        Path dir2 = asSuccess(small.materialize(repoDir, sha2)).directory();

        appendCommit(repoDir, "third");
        String sha3 = headSha(repoDir);
        Path dir3 = asSuccess(small.materialize(repoDir, sha3)).directory();

        assertThat(Files.exists(dir1)).isFalse();
        assertThat(Files.exists(dir2)).isTrue();
        assertThat(Files.exists(dir3)).isTrue();
    }
}
