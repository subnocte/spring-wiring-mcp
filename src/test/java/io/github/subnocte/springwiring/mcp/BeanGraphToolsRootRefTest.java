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
 * Wiring of the {@code root}/{@code ref} parameters into {@link BeanGraphTools}. See
 * {@link EndpointResolverToolsRootRefTest} for the parallel coverage on the endpoint tools;
 * {@link RootRefResolverTest} covers the resolution logic itself.
 */
class BeanGraphToolsRootRefTest {

    @TempDir
    Path refsBase;

    private static Path sampleProjectRoot() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                BeanGraphToolsRootRefTest.class.getResource("/sample-project")).toURI());
    }

    private BeanGraphTools toolsOver(Path defaultRoot) {
        CodeIndexesRegistry registry = new CodeIndexesRegistry(defaultRoot);
        RefMaterializer refMaterializer = new RefMaterializer(refsBase);
        return new BeanGraphTools(new RootRefResolver(registry, refMaterializer));
    }

    private static void writeAlphaService(Path root) throws IOException {
        Path file = root.resolve("src/main/java/com/example/AlphaService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class AlphaService {
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
        BeanGraphTools tools = toolsOver(sampleProjectRoot());

        var viaLegacyOverload = tools.beanDependencies("NotificationService");
        var viaFullMethod = tools.beanDependencies("NotificationService", null, null);

        assertThat(viaLegacyOverload.found()).isTrue();
        assertThat(viaLegacyOverload.bean()).isEqualTo(viaFullMethod.bean());
        assertThat(viaLegacyOverload.resolvedCommit()).isNull();
        assertThat(viaLegacyOverload.rootError()).isNull();
    }

    @Test
    void rootParameterSwitchesTheAnalyzedCodebase(@TempDir Path otherRoot) throws IOException {
        writeAlphaService(otherRoot);
        BeanGraphTools tools = toolsOver(sampleProjectRoot());

        var result = tools.beanDependencies("AlphaService", otherRoot.toString(), null);

        assertThat(result.found()).isTrue();
        assertThat(result.rootError()).isNull();
    }

    @Test
    void refParameterReportsResolvedCommit(@TempDir Path repo) throws Exception {
        writeAlphaService(repo);
        initGitRepo(repo);
        String sha = headSha(repo);
        BeanGraphTools tools = toolsOver(sampleProjectRoot());

        var result = tools.beanDependencies("AlphaService", repo.toString(), sha);

        assertThat(result.found()).isTrue();
        assertThat(result.resolvedCommit()).isNotNull();
        assertThat(result.resolvedCommit().sha()).isEqualTo(sha);
    }

    @Test
    void invalidRootIsReportedAsRootErrorNotUnknownBean(@TempDir Path parent) throws Exception {
        BeanGraphTools tools = toolsOver(sampleProjectRoot());
        Path missing = parent.resolve("does-not-exist");

        var result = tools.beanDependencies("NotificationService", missing.toString(), null);

        assertThat(result.found()).isFalse();
        assertThat(result.rootError()).isNotNull().contains(missing.toString());
    }

    @Test
    void beanDependentsLegacyOverloadStillWorks() throws Exception {
        BeanGraphTools tools = toolsOver(sampleProjectRoot());

        var viaLegacyOverload = tools.beanDependents("NotificationService");
        var viaFullMethod = tools.beanDependents("NotificationService", null, null);

        assertThat(viaLegacyOverload.found()).isTrue();
        assertThat(viaLegacyOverload.dependents()).isEqualTo(viaFullMethod.dependents());
        assertThat(viaLegacyOverload.resolvedCommit()).isNull();
    }
}
