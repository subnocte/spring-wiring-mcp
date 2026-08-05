package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointHandler;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.endpoint.UnresolvedMapping;
import io.github.subnocte.springwiring.index.CodeIndexes;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MCP tool surface for resolving Spring Boot REST endpoints to their handler methods.
 */
@Service
public class EndpointResolverTools {

    private static final int SUGGESTION_LIMIT = 5;

    private final CodeIndexes codeIndexes;

    public EndpointResolverTools(CodeIndexes codeIndexes) {
        this.codeIndexes = codeIndexes;
    }

    @McpTool(
            name = "resolveEndpoint",
            description = "Resolves an HTTP method + path to the Spring MVC controller method that handles it, "
                    + "including the source file and line number. Understands @RestController/@Controller, "
                    + "class-level @RequestMapping combined with method-level mappings, {var} path variables, "
                    + "paths referenced via static final String constants, mappings declared on implemented "
                    + "in-repo interfaces, and statically imported RequestMethod constants. Wildcard patterns "
                    + "(* / **), string-concatenation paths, and interfaces generated outside the scanned "
                    + "sources are NOT resolved; they are reported as unresolved mappings instead of being "
                    + "silently dropped. On a miss, returns close-match suggestions plus the unresolved "
                    + "mappings the endpoint might be hiding in.",
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
        EndpointIndex endpointIndex = codeIndexes.current().endpointIndex();
        List<UnresolvedMapping> unresolved = endpointIndex.unresolved();
        Optional<EndpointHandler> match = endpointIndex.resolve(method, path);
        if (match.isPresent()) {
            return EndpointResolution.found(match.get(), unresolved.size());
        }
        List<EndpointHandler> suggestions = endpointIndex.suggestClosest(method, path, SUGGESTION_LIMIT);
        return EndpointResolution.notFound(suggestions, unresolved);
    }

    @McpTool(
            name = "indexStatus",
            description = "Reports the index's coverage of the target codebase: how many endpoints and beans "
                    + "are indexed, how many source files were scanned, which mappings could not be "
                    + "resolved statically (with file, line, and reason), and how many bean injection "
                    + "sites are unresolved by reason. Call this first to judge how much to trust "
                    + "resolveEndpoint and beanDependencies results for this project.",
            annotations = @McpTool.McpAnnotations(
                    title = "Endpoint index coverage status",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public IndexStatus indexStatus() {
        CodeIndexes.Snapshot snapshot = codeIndexes.current();
        EndpointIndex endpointIndex = snapshot.endpointIndex();
        BeanIndex beanIndex = snapshot.beanIndex();
        List<UnresolvedMapping> unresolved = endpointIndex.unresolved();
        Map<String, Long> byReason = unresolved.stream()
                .collect(Collectors.groupingBy(UnresolvedMapping::reason, Collectors.counting()));
        return new IndexStatus(
                endpointIndex.all().size(),
                endpointIndex.scannedFileCount(),
                unresolved.size(),
                byReason,
                unresolved,
                beanIndex.allBeans().size(),
                beanIndex.unresolvedInjectionCountByReason(),
                endpointIndex.parseFailures().size(),
                endpointIndex.parseFailures());
    }

    @McpTool(
            name = "traceEndpoint",
            description = "Follows an HTTP endpoint down through the bean graph: resolves the handler "
                    + "method, then walks resolved bean dependencies breadth-first from the controller "
                    + "to the persistence boundary (MyBatis mappers / Spring Data repositories). Bean-"
                    + "level, not method-level: it reports which beans are reachable from the handler's "
                    + "controller, not which methods actually call which. Unresolved sites encountered "
                    + "on the way are listed as blocked, so an incomplete trace is visible as such. On "
                    + "hub-heavy codebases a full trace can be large: maxDepth limits how deep to walk "
                    + "(truncated=true marks a cut-off trace), terminalsOnly=true omits the hop list and "
                    + "returns just the persistence boundary and blocked sites.",
            annotations = @McpTool.McpAnnotations(
                    title = "Trace endpoint to persistence boundary",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public EndpointTrace traceEndpoint(
            @McpToolParam(description = "HTTP method, e.g. GET, POST, PUT, DELETE, PATCH", required = true)
            String method,
            @McpToolParam(description = "Request path, e.g. /users/42", required = true)
            String path,
            @McpToolParam(description = "Maximum hops to walk from the controller (1 = direct "
                    + "dependencies only). Omit for a full trace; truncated=true in the result "
                    + "marks a trace this limit cut off.", required = false)
            Integer maxDepth,
            @McpToolParam(description = "true to omit the hop list and return only the reached "
                    + "persistence boundary (terminals) and blocked sites", required = false)
            Boolean terminalsOnly
    ) {
        CodeIndexes.Snapshot snapshot = codeIndexes.current();
        Optional<EndpointHandler> match = snapshot.endpointIndex().resolve(method, path);
        if (match.isEmpty()) {
            return new EndpointTrace(false, null, List.of(), List.of(), List.of(), false,
                    "No indexed route matches " + method + " " + path
                            + "; use resolveEndpoint for close-match suggestions.");
        }
        BeanIndex.TraceResult trace = maxDepth == null
                ? snapshot.beanIndex().reachableFrom(match.get().fqcn())
                : snapshot.beanIndex().reachableFrom(match.get().fqcn(), maxDepth);
        List<BeanIndex.TraceStep> steps = Boolean.TRUE.equals(terminalsOnly)
                ? List.of()
                : trace.steps();
        return new EndpointTrace(true, match.get(), steps, trace.terminals(),
                trace.blocked(), trace.truncated(), null);
    }

    /** Result payload of {@link #traceEndpoint}. */
    public record EndpointTrace(
            boolean found,
            EndpointHandler handler,
            List<BeanIndex.TraceStep> steps,
            List<io.github.subnocte.springwiring.bean.BeanDefinition> terminals,
            List<BeanIndex.BlockedSite> blocked,
            boolean truncated,
            String error
    ) {
    }

    /**
     * Result payload of {@link #resolveEndpoint}. {@code unresolvedCount} is always present;
     * the full {@code unresolvedMappings} list and {@code warning} are only populated on a
     * miss, where an unresolved mapping might be the endpoint the caller was looking for.
     */
    public record EndpointResolution(
            boolean found,
            EndpointHandler match,
            List<EndpointHandler> suggestions,
            int unresolvedCount,
            List<UnresolvedMapping> unresolvedMappings,
            String warning
    ) {
        static EndpointResolution found(EndpointHandler handler, int unresolvedCount) {
            return new EndpointResolution(true, handler, List.of(), unresolvedCount, List.of(), null);
        }

        static EndpointResolution notFound(List<EndpointHandler> suggestions, List<UnresolvedMapping> unresolved) {
            String warning = unresolved.isEmpty() ? null
                    : "No indexed route matched, but " + unresolved.size() + " mapping(s) could not be "
                    + "resolved statically; the requested endpoint may be among them (see unresolvedMappings).";
            return new EndpointResolution(false, null, suggestions, unresolved.size(), unresolved, warning);
        }
    }

    /** Result payload of {@link #indexStatus}. */
    public record IndexStatus(
            int endpointCount,
            int scannedFileCount,
            int unresolvedCount,
            Map<String, Long> unresolvedByReason,
            List<UnresolvedMapping> unresolvedMappings,
            int beanCount,
            Map<String, Long> unresolvedInjectionsByReason,
            int parseFailureCount,
            List<io.github.subnocte.springwiring.scanner.ParseFailure> parseFailures
    ) {
    }
}
