package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.endpoint.UnresolvedMapping;
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
    private static EndpointResolverTools tools;

    @BeforeAll
    static void setUp() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                EndpointResolverToolsTest.class.getResource("/sample-project")).toURI());
        index = EndpointIndex.forRoot(root);
        tools = new EndpointResolverTools(index);
    }

    @Test
    void missResponseCarriesUnresolvedMappingsAndWarning() {
        var result = tools.resolveEndpoint("GET", "/definitely/not/there");

        assertThat(result.found()).isFalse();
        assertThat(result.suggestions()).isNotEmpty();
        assertThat(result.unresolvedCount()).isEqualTo(2);
        assertThat(result.unresolvedMappings())
                .hasSize(2)
                .extracting(UnresolvedMapping::reason)
                .containsExactlyInAnyOrder(
                        UnresolvedMapping.REASON_CONSTANT_REFERENCE,
                        UnresolvedMapping.REASON_UNSUPPORTED_PATTERN);
        assertThat(result.warning()).contains("2");
    }

    @Test
    void hitResponseStaysLeanButReportsUnresolvedCount() {
        var result = tools.resolveEndpoint("GET", "/health");

        assertThat(result.found()).isTrue();
        assertThat(result.match().methodName()).isEqualTo("health");
        assertThat(result.unresolvedCount()).isEqualTo(2);
        assertThat(result.unresolvedMappings()).isEmpty();
        assertThat(result.warning()).isNull();
    }

    @Test
    void indexStatusSummarizesCoverage() {
        var status = tools.indexStatus();

        assertThat(status.endpointCount()).isEqualTo(index.all().size());
        assertThat(status.scannedFileCount()).isEqualTo(index.scannedFileCount());
        assertThat(status.unresolvedCount()).isEqualTo(2);
        assertThat(status.unresolvedByReason())
                .containsEntry(UnresolvedMapping.REASON_CONSTANT_REFERENCE, 1L)
                .containsEntry(UnresolvedMapping.REASON_UNSUPPORTED_PATTERN, 1L);
        assertThat(status.unresolvedMappings()).hasSize(2);
    }
}
