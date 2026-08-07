package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.TestIndexesRegistry;
import io.github.subnocte.springwiring.testwiring.TestWiringIndex;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP tool surface for a JUnit5 test class's declared test-side wiring: which types it
 * builds as real (non-doubled) subjects, which it doubles (and how), which types
 * {@code mockStatic} freezes, and — reversed — which tests declare a given type in each of
 * those roles. See {@link io.github.subnocte.springwiring.testwiring.TestWiringIndex} for
 * exactly what "declared" means and this analysis's non-goals.
 *
 * <p>Composes {@link RootRefResolver} (shared root/ref resolution — resolving {@code ref}
 * against the effective root's repository is identical to the six production tools) with
 * {@link TestIndexesRegistry} (test-wiring lookup against whatever directory root/ref
 * resolved to, cached and fingerprinted independently of the production index).
 */
@Service
public class TestWiringTools {

    private static final String ROOT_PARAM_DESCRIPTION = "Directory to analyze instead of "
            + "this server's startup code.root. Absolute path.";
    private static final String REF_PARAM_DESCRIPTION = "Git ref (branch, tag, or commit) to "
            + "analyze instead of the working tree, resolved in the repository at the "
            + "effective root (root, or code.root if omitted). Re-resolved on every call, so "
            + "a moved branch is picked up without restarting the server. The response's "
            + "resolvedCommit reports exactly which commit was used.";

    private final RootRefResolver resolver;
    private final TestIndexesRegistry testIndexesRegistry;

    public TestWiringTools(RootRefResolver resolver, TestIndexesRegistry testIndexesRegistry) {
        this.resolver = resolver;
        this.testIndexesRegistry = testIndexesRegistry;
    }

    @McpTool(
            name = "testWiring",
            description = "Resolves a JUnit5 test class's declared test-side wiring: real (non-doubled) "
                    + "subjects (@InjectMocks fields, or new/mock()/spy() construction via field "
                    + "initializer or @BeforeEach assignment), test doubles (@Mock/@Spy/@MockBean/"
                    + "@SpyBean/@MockitoBean/@MockitoSpyBean fields, or mock()/spy() calls, kind-tagged), "
                    + "mockStatic(...) freezes with their scope (method name, @BeforeEach, or field), "
                    + "and the class's Spring test-slice annotations (@SpringBootTest, "
                    + "@WebMvcTest(controllers=...), etc.) plus @ExtendWith contents. @Nested classes "
                    + "are aggregated under the top-level class with their nested scope recorded; an "
                    + "outer field's scope covers its @Nested classes too, matching real JUnit5 "
                    + "semantics. This is a declaration-level analysis — whether a Spring context "
                    + "actually starts, or execution actually reaches a given type, is never claimed. "
                    + "A declaration that cannot be resolved statically (an annotation whose import "
                    + "doesn't match the known type, a mockStatic argument that isn't a class literal) "
                    + "is reported in unresolved with a reason instead of being guessed.",
            annotations = @McpTool.McpAnnotations(
                    title = "Resolve a test class's declared wiring",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public TestWiringResult testWiring(
            @McpToolParam(description = "Test class: fully qualified name, or a simple name if unique",
                    required = true)
            String testClass,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        Resolution resolution = resolve(root, ref);
        if (resolution.rootError() != null) {
            return TestWiringResult.rootError(resolution.rootError());
        }
        List<TestWiringIndex.TestClassWiring> matches = resolution.index().findByName(testClass);
        TestWiringResult result;
        if (matches.isEmpty()) {
            result = TestWiringResult.notFound(
                    "No scanned test class named '" + testClass + "'. It may be outside the test "
                            + "source set, or outside CODE_ROOT.");
        } else if (matches.size() > 1) {
            result = TestWiringResult.ambiguous(matches.stream().map(TestWiringIndex.TestClassWiring::fqcn).toList());
        } else {
            result = TestWiringResult.found(matches.get(0));
        }
        return result.withRootInfo(resolution.resolvedCommit(), resolution.notices());
    }

    @McpTool(
            name = "testDoubleUsage",
            description = "Reverse lookup for a class or interface: which test classes declare it as a "
                    + "real (non-doubled) subject, which mock/spy it, and which freeze it via "
                    + "mockStatic — three declaration-based buckets, mirroring testWiring's "
                    + "classification. Also reports unclassifiedContextTests: Spring context-slice "
                    + "tests (@SpringBootTest/@WebMvcTest/etc.) whose declarations never mention the "
                    + "type at all. Absence from that list is NOT proof of safety — resolving what a "
                    + "Spring context actually wires at runtime is out of scope for this static "
                    + "analysis, so these are self-reported as unclassified rather than silently "
                    + "assumed unaffected.",
            annotations = @McpTool.McpAnnotations(
                    title = "Find tests that double a given type",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public TestDoubleUsageResult testDoubleUsage(
            @McpToolParam(description = "Fully qualified class or interface name", required = true)
            String fqcn,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        Resolution resolution = resolve(root, ref);
        if (resolution.rootError() != null) {
            return TestDoubleUsageResult.rootError(resolution.rootError());
        }
        TestWiringIndex index = resolution.index();
        TestDoubleUsageResult result = new TestDoubleUsageResult(
                fqcn,
                index.realSubjectClasses(fqcn),
                index.mockedClasses(fqcn),
                index.staticMockedClasses(fqcn),
                index.unclassifiedContextTests(fqcn),
                null, List.of(), null);
        return result.withRootInfo(resolution.resolvedCommit(), resolution.notices());
    }

    /**
     * Resolves root/ref via {@link RootRefResolver} (identical to the production tools),
     * then looks up the test-wiring index for the exact same resolved directory — so a
     * materialized ref's test sources (see {@link io.github.subnocte.springwiring.ref.RefMaterializer})
     * are what gets analyzed, not the live working tree.
     */
    private Resolution resolve(String root, String ref) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return Resolution.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        TestIndexesRegistry.Lookup lookup = testIndexesRegistry.forRoot(success.indexes().root());
        if (lookup instanceof TestIndexesRegistry.Failure failure) {
            return Resolution.rootError(failure.reason());
        }
        TestIndexesRegistry.Success testSuccess = (TestIndexesRegistry.Success) lookup;
        List<String> notices = new ArrayList<>(success.notices());
        notices.addAll(testSuccess.notices());
        return new Resolution(testSuccess.indexes().current(), success.resolvedCommit(), notices, null);
    }

    private record Resolution(
            TestWiringIndex index, ResolvedCommit resolvedCommit, List<String> notices, String rootError) {
        static Resolution rootError(String reason) {
            return new Resolution(null, null, List.of(), reason);
        }
    }

    /**
     * Result payload of {@link #testWiring}. On an ambiguous simple name, {@code matches}
     * lists the FQCNs to retry with. {@code rootError} is set instead of every other field
     * when root/ref resolution itself failed, distinct from a domain miss (no such class).
     */
    public record TestWiringResult(
            boolean found,
            TestWiringIndex.TestClassWiring wiring,
            List<String> matches,
            String error,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static TestWiringResult found(TestWiringIndex.TestClassWiring wiring) {
            return new TestWiringResult(true, wiring, List.of(), null, null, List.of(), null);
        }

        static TestWiringResult notFound(String error) {
            return new TestWiringResult(false, null, List.of(), error, null, List.of(), null);
        }

        static TestWiringResult ambiguous(List<String> matches) {
            return new TestWiringResult(false, null, matches,
                    "Simple name matches " + matches.size() + " test classes; retry with a fully qualified name.",
                    null, List.of(), null);
        }

        static TestWiringResult rootError(String reason) {
            return new TestWiringResult(false, null, List.of(), null, null, List.of(), reason);
        }

        TestWiringResult withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new TestWiringResult(found, wiring, matches, error, resolvedCommit, rootNotices, rootError);
        }
    }

    /**
     * Result payload of {@link #testDoubleUsage}. {@code rootError} is set instead of
     * every coverage field when root/ref resolution itself failed.
     */
    public record TestDoubleUsageResult(
            String fqcn,
            List<String> realSubject,
            List<String> mocked,
            List<String> staticMocked,
            List<String> unclassifiedContextTests,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static TestDoubleUsageResult rootError(String reason) {
            return new TestDoubleUsageResult(null, List.of(), List.of(), List.of(), List.of(), null, List.of(), reason);
        }

        TestDoubleUsageResult withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new TestDoubleUsageResult(fqcn, realSubject, mocked, staticMocked, unclassifiedContextTests,
                    resolvedCommit, rootNotices, rootError);
        }
    }
}
