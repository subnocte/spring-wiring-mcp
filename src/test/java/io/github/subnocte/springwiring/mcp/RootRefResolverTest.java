package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.ref.RefMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RootRefResolver} is what every MCP tool delegates root/ref handling to, so this
 * is tested once here rather than reimplemented (and re-verified) six times per tool.
 * Covers: default behavior when both are omitted, switching the analyzed directory via
 * {@code root}, pinning to a past commit via {@code ref} without disturbing the live root,
 * composing root+ref together, self-reported failures, and the fixed single-index mode
 * that keeps pre-root/ref tool constructors working unchanged.
 */
class RootRefResolverTest {

    @TempDir
    Path defaultRoot;

    @TempDir
    Path refsBase;

    private CodeIndexesRegistry registry;
    private RootRefResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        writeController(defaultRoot, "Default", "/default-alpha");
        registry = new CodeIndexesRegistry(defaultRoot);
        resolver = new RootRefResolver(registry, new RefMaterializer(refsBase));
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

    private static void initGitRepo(Path repo) throws IOException, InterruptedException {
        run(repo, "git", "init", "-b", "main");
        commit(repo, "initial");
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "commit", "-m", message);
    }

    private static String headSha(Path repo) throws IOException, InterruptedException {
        return runCapture(repo, "git", "rev-parse", "HEAD");
    }

    private static RootRefResolver.Success asSuccess(RootRefResolver.Result result) {
        assertThat(result).isInstanceOf(RootRefResolver.Success.class);
        return (RootRefResolver.Success) result;
    }

    private static RootRefResolver.Failure asFailure(RootRefResolver.Result result) {
        assertThat(result).isInstanceOf(RootRefResolver.Failure.class);
        return (RootRefResolver.Failure) result;
    }

    @Test
    void omittingBothUsesTheDefaultRootWithNoResolvedCommit() {
        RootRefResolver.Success success = asSuccess(resolver.resolve(null, null));

        assertThat(success.indexes().current().endpointIndex().resolve("GET", "/default-alpha")).isPresent();
        assertThat(success.resolvedCommit()).isNull();
        assertThat(success.notices()).isEmpty();
    }

    @Test
    void explicitRootSwitchesTheAnalyzedDirectory(@TempDir Path otherRoot) throws IOException {
        writeController(otherRoot, "Other", "/other-alpha");

        RootRefResolver.Success success = asSuccess(resolver.resolve(otherRoot.toString(), null));

        assertThat(success.indexes().current().endpointIndex().resolve("GET", "/other-alpha")).isPresent();
        assertThat(success.indexes().current().endpointIndex().resolve("GET", "/default-alpha")).isEmpty();
    }

    @Test
    void invalidRootIsReportedNotThrown(@TempDir Path parent) {
        Path missing = parent.resolve("does-not-exist");

        RootRefResolver.Failure failure = asFailure(resolver.resolve(missing.toString(), null));

        assertThat(failure.reason()).contains(missing.toString());
    }

    @Test
    void refPinsToAPastCommitWithoutDisturbingTheLiveRoot() throws Exception {
        initGitRepo(defaultRoot);
        String firstSha = headSha(defaultRoot);
        writeController(defaultRoot, "Second", "/second-alpha");
        commit(defaultRoot, "add second controller");

        RootRefResolver.Success atFirstCommit = asSuccess(resolver.resolve(null, firstSha));
        assertThat(atFirstCommit.indexes().current().endpointIndex().resolve("GET", "/default-alpha")).isPresent();
        assertThat(atFirstCommit.indexes().current().endpointIndex().resolve("GET", "/second-alpha")).isEmpty();
        assertThat(atFirstCommit.resolvedCommit()).isNotNull();
        assertThat(atFirstCommit.resolvedCommit().ref()).isEqualTo(firstSha);
        assertThat(atFirstCommit.resolvedCommit().sha()).isEqualTo(firstSha);
        assertThat(atFirstCommit.resolvedCommit().committedAt()).isNotBlank();

        RootRefResolver.Success liveRoot = asSuccess(resolver.resolve(null, null));
        assertThat(liveRoot.indexes().current().endpointIndex().resolve("GET", "/second-alpha")).isPresent();
        assertThat(liveRoot.resolvedCommit()).isNull();
    }

    @Test
    void rootAndRefComposeAgainstTheSpecifiedRootsOwnRepository(@TempDir Path otherRoot) throws Exception {
        writeController(otherRoot, "Other", "/other-alpha");
        initGitRepo(otherRoot);
        String sha = headSha(otherRoot);

        RootRefResolver.Success success = asSuccess(resolver.resolve(otherRoot.toString(), sha));

        assertThat(success.indexes().current().endpointIndex().resolve("GET", "/other-alpha")).isPresent();
        assertThat(success.resolvedCommit().sha()).isEqualTo(sha);
    }

    @Test
    void unknownRefIsReportedNotThrown() throws Exception {
        initGitRepo(defaultRoot);

        RootRefResolver.Failure failure = asFailure(resolver.resolve(null, "no-such-ref-xyz"));

        assertThat(failure.reason()).contains("no-such-ref-xyz");
    }

    @Test
    void fixedModeSupportsOnlyBothParametersOmitted() {
        CodeIndexes indexes = CodeIndexes.forRoot(defaultRoot);
        RootRefResolver fixed = RootRefResolver.fixed(indexes);

        RootRefResolver.Success success = asSuccess(fixed.resolve(null, null));
        assertThat(success.indexes()).isSameAs(indexes);
        assertThat(success.resolvedCommit()).isNull();

        RootRefResolver.Failure rootFailure = asFailure(fixed.resolve(defaultRoot.toString(), null));
        assertThat(rootFailure.reason()).isNotBlank();
        RootRefResolver.Failure refFailure = asFailure(fixed.resolve(null, "main"));
        assertThat(refFailure.reason()).isNotBlank();
    }

    @Test
    void knownRootsClassifiesDefaultAdditionalRootsAndMaterializedRefs(@TempDir Path otherRoot) throws Exception {
        writeController(otherRoot, "Other", "/other-alpha");
        initGitRepo(defaultRoot);
        String sha = headSha(defaultRoot);

        resolver.resolve(otherRoot.toString(), null);
        resolver.resolve(null, sha);

        List<KnownRoot> known = resolver.knownRoots();

        assertThat(known).extracting(KnownRoot::kind)
                .contains(KnownRoot.KIND_DEFAULT, KnownRoot.KIND_ROOT, KnownRoot.KIND_REF);
        assertThat(known).extracting(KnownRoot::path).contains(defaultRoot.toAbsolutePath().normalize().toString());
    }
}
