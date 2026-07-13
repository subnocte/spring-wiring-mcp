package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.endpoint.EndpointHandler;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * MCP tool surface for resolving Spring Boot REST endpoints to their handler methods.
 */
@Service
public class EndpointResolverTools {

    private static final int SUGGESTION_LIMIT = 5;

    private final EndpointIndex endpointIndex;

    public EndpointResolverTools(EndpointIndex endpointIndex) {
        this.endpointIndex = endpointIndex;
    }

    @McpTool(
            name = "resolveEndpoint",
            description = "Resolves an HTTP method + path to the Spring MVC controller method that handles it, "
                    + "including the source file and line number. Falls back to close-match suggestions when "
                    + "no exact route is found.",
            // Pure lookup against an in-memory index built from local sources: safe to
            // call freely, safe to retry, and touches nothing outside the indexed codebase.
            annotations = @McpTool.McpAnnotations(
                    title = "Resolve Spring REST endpoint",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public EndpointResolution resolveEndpoint(
            @McpToolParam(description = "HTTP method, e.g. GET, POST, PUT, DELETE, PATCH", required = true)
            String method,
            @McpToolParam(description = "Request path, e.g. /users/42", required = true)
            String path
    ) {
        Optional<EndpointHandler> match = endpointIndex.resolve(method, path);
        if (match.isPresent()) {
            return EndpointResolution.found(match.get());
        }
        List<EndpointHandler> suggestions = endpointIndex.suggestClosest(method, path, SUGGESTION_LIMIT);
        return EndpointResolution.notFound(suggestions);
    }

    /** Result payload returned by {@link #resolveEndpoint}. */
    public record EndpointResolution(boolean found, EndpointHandler match, List<EndpointHandler> suggestions) {
        static EndpointResolution found(EndpointHandler handler) {
            return new EndpointResolution(true, handler, List.of());
        }

        static EndpointResolution notFound(List<EndpointHandler> suggestions) {
            return new EndpointResolution(false, null, suggestions);
        }
    }
}
