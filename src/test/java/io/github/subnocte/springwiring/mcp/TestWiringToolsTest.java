package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.index.TestIndexesRegistry;
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
 * {@link TestWiringTools} composes {@link RootRefResolver} (root/ref resolution, shared
 * with the production tools) with {@link TestIndexesRegistry} (test-wiring analysis of
 * whatever directory root/ref resolved to). Fixtures live under
 * {@code src/test/resources/sample-project}, package {@code com.example.sample.testwiring}
 * (see {@link io.github.subnocte.springwiring.testwiring.TestWiringIndexTest} for the AST
 * layer these tools sit on top of).
 */
class TestWiringToolsTest {

    private static final String PKG = "com.example.sample.testwiring.";

    @TempDir
    Path refsBase;

    private static Path sampleProjectRoot() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                TestWiringToolsTest.class.getResource("/sample-project")).toURI());
    }

    private TestWiringTools toolsOver(Path defaultRoot) {
        CodeIndexesRegistry codeIndexesRegistry = new CodeIndexesRegistry(defaultRoot);
        RefMaterializer refMaterializer = new RefMaterializer(refsBase);
        RootRefResolver resolver = new RootRefResolver(codeIndexesRegistry, refMaterializer);
        TestIndexesRegistry testIndexesRegistry = new TestIndexesRegistry(defaultRoot, 8);
        return new TestWiringTools(resolver, testIndexesRegistry);
    }

    private static void writeMinimalTestClass(Path root, String name) throws IOException {
        Path file = root.resolve("src/test/java/com/example/" + name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                class %s {
                }
                """.formatted(name));
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
    void testWiringResolvesByExactFqcn() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testWiring(PKG + "MockitoExtensionFieldsTest", null, null);

        assertThat(result.found()).isTrue();
        assertThat(result.wiring().mocked()).hasSize(1);
        assertThat(result.rootError()).isNull();
    }

    @Test
    void testWiringResolvesByUniqueSimpleName() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testWiring("MockitoExtensionFieldsTest", null, null);

        assertThat(result.found()).isTrue();
        assertThat(result.wiring().fqcn()).isEqualTo(PKG + "MockitoExtensionFieldsTest");
    }

    @Test
    void testWiringReportsNotFoundForUnknownClass() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testWiring("NoSuchTestClassXyz", null, null);

        assertThat(result.found()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void testWiringRootParameterSwitchesTheAnalyzedCodebase(@TempDir Path otherRoot) throws Exception {
        writeMinimalTestClass(otherRoot, "AlphaTest");
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testWiring("AlphaTest", otherRoot.toString(), null);

        assertThat(result.found()).isTrue();
        assertThat(result.rootError()).isNull();
    }

    @Test
    void testWiringInvalidRootIsReportedAsRootError(@TempDir Path parent) throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());
        Path missing = parent.resolve("does-not-exist");

        var result = tools.testWiring("MockitoExtensionFieldsTest", missing.toString(), null);

        assertThat(result.found()).isFalse();
        assertThat(result.rootError()).isNotNull().contains(missing.toString());
    }

    @Test
    void testWiringRefParameterReportsResolvedCommitAndAnalyzesTestSourcesAtThatCommit(
            @TempDir Path repo) throws Exception {
        writeMinimalTestClass(repo, "AlphaTest");
        initGitRepo(repo);
        String sha = headSha(repo);
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testWiring("AlphaTest", repo.toString(), sha);

        assertThat(result.found()).isTrue();
        assertThat(result.resolvedCommit()).isNotNull();
        assertThat(result.resolvedCommit().sha()).isEqualTo(sha);
    }

    @Test
    void testDoubleUsageClassifiesDeclaredRoles() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testDoubleUsage("com.example.sample.beans.PaymentGateway", null, null);

        assertThat(result.mocked()).contains(
                PKG + "MockitoExtensionFieldsTest", PKG + "MockitoBeanFieldsTest", PKG + "LegacyMockBeanFieldsTest");
        assertThat(result.rootError()).isNull();
    }

    @Test
    void testDoubleUsageClassifiesRealSubjectAndStaticMocked() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var checkoutResult = tools.testDoubleUsage("com.example.sample.beans.CheckoutService", null, null);
        assertThat(checkoutResult.realSubject()).contains(PKG + "MockitoExtensionFieldsTest");

        var stripeResult = tools.testDoubleUsage("com.example.sample.beans.StripeGateway", null, null);
        assertThat(stripeResult.staticMocked()).contains(
                PKG + "MockStaticInMethodTest", PKG + "MockStaticInBeforeEachTest", PKG + "MockStaticAsFieldTest");
    }

    @Test
    void testDoubleUsageReportsUnclassifiedContextTests() throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());

        var result = tools.testDoubleUsage("com.example.sample.beans.NotificationService", null, null);

        assertThat(result.unclassifiedContextTests()).contains(
                PKG + "MockitoBeanFieldsTest", PKG + "LegacyMockBeanFieldsTest", PKG + "WebMvcSliceTest");
    }

    @Test
    void testDoubleUsageInvalidRootIsReportedAsRootError(@TempDir Path parent) throws Exception {
        TestWiringTools tools = toolsOver(sampleProjectRoot());
        Path missing = parent.resolve("does-not-exist");

        var result = tools.testDoubleUsage("com.example.sample.beans.PaymentGateway", missing.toString(), null);

        assertThat(result.rootError()).isNotNull().contains(missing.toString());
    }
}
