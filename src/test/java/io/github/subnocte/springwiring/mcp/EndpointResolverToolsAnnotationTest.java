package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the MCP tool specification generated from {@link EndpointResolverTools}
 * carries the declared tool annotations (read-only, non-destructive, idempotent,
 * closed-world). Uses the annotation provider directly; no Spring context or MCP
 * transport is started.
 */
class EndpointResolverToolsAnnotationTest {

    @Test
    void resolveEndpointToolDeclaresReadOnlySemantics() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                getClass().getResource("/sample-project")).toURI());
        EndpointResolverTools tools = new EndpointResolverTools(EndpointIndex.forRoot(root));

        List<SyncToolSpecification> specs = new SyncMcpToolProvider(List.of(tools)).getToolSpecifications();

        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("resolveEndpoint", "indexStatus");
        for (SyncToolSpecification spec : specs) {
            McpSchema.Tool tool = spec.tool();
            McpSchema.ToolAnnotations annotations = tool.annotations();
            assertThat(annotations).as("annotations of %s", tool.name()).isNotNull();
            assertThat(annotations.readOnlyHint()).isTrue();
            assertThat(annotations.destructiveHint()).isFalse();
            assertThat(annotations.idempotentHint()).isTrue();
            assertThat(annotations.openWorldHint()).isFalse();
        }
    }
}
