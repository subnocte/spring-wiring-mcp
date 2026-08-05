package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanEdge;
import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.endpoint.UnresolvedMapping;
import io.github.subnocte.springwiring.index.CodeIndexes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the MCP tool layer over the sample-project index: unresolved mappings must
 * surface in miss responses and in indexStatus, so the tool never fails silently.
 */
class EndpointResolverToolsTest {

    private static EndpointIndex index;
    private static BeanIndex beanIndex;
    private static EndpointResolverTools tools;

    @BeforeAll
    static void setUp() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                EndpointResolverToolsTest.class.getResource("/sample-project")).toURI());
        index = EndpointIndex.forRoot(root);
        beanIndex = BeanIndex.forRoot(root);
        tools = new EndpointResolverTools(CodeIndexes.forRoot(root));
    }

    @Test
    void missResponseCarriesUnresolvedMappingsAndWarning() {
        var result = tools.resolveEndpoint("GET", "/definitely/not/there");

        assertThat(result.found()).isFalse();
        assertThat(result.suggestions()).isNotEmpty();
        assertThat(result.unresolvedCount()).isEqualTo(4);
        assertThat(result.unresolvedMappings())
                .hasSize(4)
                .extracting(UnresolvedMapping::reason)
                .containsExactlyInAnyOrder(
                        UnresolvedMapping.REASON_NON_LITERAL_EXPRESSION,
                        UnresolvedMapping.REASON_UNSUPPORTED_PATTERN,
                        UnresolvedMapping.REASON_INTERFACE_MAPPINGS_NOT_FOUND,
                        UnresolvedMapping.REASON_CONSTANT_REFERENCE);
        assertThat(result.warning()).contains("4");
    }

    @Test
    void hitResponseStaysLeanButReportsUnresolvedCount() {
        var result = tools.resolveEndpoint("GET", "/health");

        assertThat(result.found()).isTrue();
        assertThat(result.match().methodName()).isEqualTo("health");
        assertThat(result.unresolvedCount()).isEqualTo(4);
        assertThat(result.unresolvedMappings()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void indexStatusSummarizesCoverage() {
        var status = tools.indexStatus();

        assertThat(status.endpointCount()).isEqualTo(index.all().size());
        assertThat(status.scannedFileCount()).isEqualTo(index.scannedFileCount());
        assertThat(status.unresolvedCount()).isEqualTo(4);
        assertThat(status.unresolvedByReason())
                .containsEntry(UnresolvedMapping.REASON_NON_LITERAL_EXPRESSION, 1L)
                .containsEntry(UnresolvedMapping.REASON_UNSUPPORTED_PATTERN, 1L)
                .containsEntry(UnresolvedMapping.REASON_INTERFACE_MAPPINGS_NOT_FOUND, 1L)
                .containsEntry(UnresolvedMapping.REASON_CONSTANT_REFERENCE, 1L);
        assertThat(status.unresolvedMappings()).hasSize(4);
        assertThat(status.beanCount()).isGreaterThanOrEqualTo(18);
        assertThat(status.unresolvedInjectionsByReason())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        BeanEdge.REASON_MULTIPLE_CANDIDATES, 1L,
                        BeanEdge.REASON_CONDITIONAL_CANDIDATES, 1L,
                        BeanEdge.REASON_NO_IMPLEMENTATION_FOUND, 2L,
                        BeanEdge.REASON_COLLECTION_INJECTION, 1L));
        assertThat(status.parseFailureCount()).isEqualTo(1);
        assertThat(status.parseFailures().get(0).filePath()).endsWith("Unparseable.java");
    }

    @Test
    void traceEndpointWalksToTerminals() {
        var trace = tools.traceEndpoint("GET", "/notifications", null, null);

        assertThat(trace.found()).isTrue();
        assertThat(trace.handler().fqcn()).isEqualTo("com.example.sample.beans.NotificationController");
        assertThat(trace.steps()).isNotEmpty();
        assertThat(trace.terminals())
                .extracting(io.github.subnocte.springwiring.bean.BeanDefinition::fqcn)
                .contains("com.example.sample.beans.AuditMapper");
    }

    @Test
    void traceEndpointMissIsReported() {
        var trace = tools.traceEndpoint("GET", "/nope", null, null);

        assertThat(trace.found()).isFalse();
        assertThat(trace.error()).contains("resolveEndpoint");
    }

    @Test
    void traceEndpointMaxDepthCutsOffAndSelfReportsTruncation() {
        var trace = tools.traceEndpoint("GET", "/notifications", 1, null);

        // depth 1 = the controller's direct dependencies only
        assertThat(trace.steps()).hasSize(1);
        assertThat(trace.steps().get(0).to().fqcn())
                .isEqualTo("com.example.sample.beans.NotificationService");
        // NotificationService's own edges were never walked: the result must say so
        assertThat(trace.truncated()).isTrue();

        var full = tools.traceEndpoint("GET", "/notifications", 10, null);
        assertThat(full.truncated()).isFalse();
        assertThat(full.steps().size())
                .isEqualTo(tools.traceEndpoint("GET", "/notifications", null, null).steps().size());
    }

    @Test
    void traceEndpointTerminalsOnlyOmitsHops() {
        var trace = tools.traceEndpoint("GET", "/notifications", null, true);

        assertThat(trace.steps()).isEmpty();
        assertThat(trace.terminals())
                .extracting(io.github.subnocte.springwiring.bean.BeanDefinition::fqcn)
                .contains("com.example.sample.beans.AuditMapper");
        assertThat(trace.truncated()).isFalse();
    }
}
