package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.ref.RefMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring of the {@code root}/{@code ref} parameters into {@link EndpointResolverTools}.
 * {@link RootRefResolverTest} already covers the resolution logic itself in depth; this
 * confirms the tool layer actually threads it through: the legacy overloads behave exactly
 * as before, {@code root} switches the analyzed codebase, {@code ref} surfaces a
 * {@link ResolvedCommit}, and a bad {@code root} is reported as {@code rootError} rather
 * than a false "not found".
 */
class EndpointResolverToolsRootRefTest {

    @TempDir
    Path refsBase;

    private static Path sampleProjectRoot() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                EndpointResolverToolsRootRefTest.class.getResource("/sample-project")).toURI());
    }

    private EndpointResolverTools toolsOver(Path defaultRoot) {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);
        RefMaterializer refMaterializer = new RefMaterializer(refsBase);
        return new EndpointResolverTools(new RootRefResolver(registry, refMaterializer));
    }

    private static void writeAlphaController(Path root) throws IOException {
        Path file = root.resolve("src/main/java/com/example/AlphaController.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class AlphaController {

                    @GetMapping("/alpha-only")
                    public String alpha() {
                        return "a";
                    }
                }
                """);
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
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "commit", "-m", "initial");
    }

    private static String headSha(Path repo) throws IOException, InterruptedException {
        return runCapture(repo, "git", "rev-parse", "HEAD");
    }

    @Test
    void omittingRootAndRefBehavesExactlyLikeTheLegacyOverload() throws Exception {
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());

        var viaLegacyOverload = tools.resolveEndpoint("GET", "/health");
        var viaFullMethod = tools.resolveEndpoint("GET", "/health", null, null);

        assertThat(viaLegacyOverload.found()).isTrue();
        assertThat(viaLegacyOverload.match()).isEqualTo(viaFullMethod.match());
        assertThat(viaLegacyOverload.resolvedCommit()).isNull();
        assertThat(viaFullMethod.resolvedCommit()).isNull();
        assertThat(viaLegacyOverload.rootError()).isNull();
    }

    @Test
    void rootParameterSwitchesTheAnalyzedCodebase(@TempDir Path otherRoot) throws Exception {
        writeAlphaController(otherRoot);
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());

        var result = tools.resolveEndpoint("GET", "/alpha-only", otherRoot.toString(), null);

        assertThat(result.found()).isTrue();
        assertThat(result.rootError()).isNull();
    }

    @Test
    void refParameterReportsResolvedCommit(@TempDir Path repo) throws Exception {
        writeAlphaController(repo);
        initGitRepo(repo);
        String sha = headSha(repo);
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());

        var result = tools.resolveEndpoint("GET", "/alpha-only", repo.toString(), sha);

        assertThat(result.found()).isTrue();
        assertThat(result.resolvedCommit()).isNotNull();
        assertThat(result.resolvedCommit().sha()).isEqualTo(sha);
        assertThat(result.resolvedCommit().ref()).isEqualTo(sha);
    }

    @Test
    void invalidRootIsReportedAsRootErrorNotAMiss(@TempDir Path parent) throws Exception {
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());
        Path missing = parent.resolve("does-not-exist");

        var result = tools.resolveEndpoint("GET", "/health", missing.toString(), null);

        assertThat(result.found()).isFalse();
        assertThat(result.rootError()).isNotNull();
        assertThat(result.rootError()).contains(missing.toString());
    }

    @Test
    void indexStatusReportsKnownRootsAndSupportsTheLegacyOverload() throws Exception {
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());

        var status = tools.indexStatus();

        assertThat(status.knownRoots()).isNotEmpty();
        assertThat(status.rootError()).isNull();
    }

    @Test
    void traceEndpointLegacyOverloadStillWorksAndOmitsRootInfo() throws Exception {
        EndpointResolverTools tools = toolsOver(sampleProjectRoot());

        var viaLegacy = tools.traceEndpoint("GET", "/notifications", null, null);
        var viaFull = tools.traceEndpoint("GET", "/notifications", null, null, null, null);

        assertThat(viaLegacy.found()).isTrue();
        assertThat(viaLegacy.terminals()).isEqualTo(viaFull.terminals());
        assertThat(viaLegacy.resolvedCommit()).isNull();
        assertThat(viaLegacy.rootError()).isNull();
    }
}
