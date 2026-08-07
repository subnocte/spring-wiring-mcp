package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointHandler;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.endpoint.UnresolvedMapping;
import io.github.subnocte.springwiring.index.CodeIndexes;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String ROOT_PARAM_DESCRIPTION = "Directory to analyze instead of "
            + "this server's startup code.root. Absolute path.";
    private static final String REF_PARAM_DESCRIPTION = "Git ref (branch, tag, or commit) to "
            + "analyze instead of the working tree, resolved in the repository at the "
            + "effective root (root, or code.root if omitted). Re-resolved on every call, so "
            + "a moved branch is picked up without restarting the server. The response's "
            + "resolvedCommit reports exactly which commit was used.";

    private final RootRefResolver resolver;

    /**
     * Single-root construction predating root/ref support: root/ref parameters left at
     * their default (both omitted) behave exactly as before; supplying either is reported
     * as unsupported. Kept for callers that only ever analyze one fixed codebase.
     */
    public EndpointResolverTools(CodeIndexes codeIndexes) {
        this(RootRefResolver.fixed(codeIndexes));
    }

    @Autowired
    public EndpointResolverTools(RootRefResolver resolver) {
        this.resolver = resolver;
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
            String path,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return EndpointResolution.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        EndpointIndex endpointIndex = success.indexes().current().endpointIndex();
        List<UnresolvedMapping> unresolved = endpointIndex.unresolved();
        Optional<EndpointHandler> match = endpointIndex.resolve(method, path);
        EndpointResolution result = match.isPresent()
                ? EndpointResolution.found(match.get(), unresolved.size())
                : EndpointResolution.notFound(endpointIndex.suggestClosest(method, path, SUGGESTION_LIMIT), unresolved);
        return result.withRootInfo(success.resolvedCommit(), success.notices());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public EndpointResolution resolveEndpoint(String method, String path) {
        return resolveEndpoint(method, path, null, null);
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
    public IndexStatus indexStatus(
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return IndexStatus.rootError(failure.reason(), resolver.knownRoots());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        CodeIndexes.Snapshot snapshot = success.indexes().current();
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
                endpointIndex.parseFailures(),
                success.resolvedCommit(),
                success.notices(),
                null,
                resolver.knownRoots());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public IndexStatus indexStatus() {
        return indexStatus(null, null);
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
            Boolean terminalsOnly,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return EndpointTrace.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        CodeIndexes.Snapshot snapshot = success.indexes().current();
        Optional<EndpointHandler> match = snapshot.endpointIndex().resolve(method, path);
        EndpointTrace result;
        if (match.isEmpty()) {
            result = new EndpointTrace(false, null, List.of(), List.of(), List.of(), false,
                    "No indexed route matches " + method + " " + path
                            + "; use resolveEndpoint for close-match suggestions.",
                    null, List.of(), null);
        } else {
            BeanIndex.TraceResult trace = maxDepth == null
                    ? snapshot.beanIndex().reachableFrom(match.get().fqcn())
                    : snapshot.beanIndex().reachableFrom(match.get().fqcn(), maxDepth);
            List<BeanIndex.TraceStep> steps = Boolean.TRUE.equals(terminalsOnly)
                    ? List.of()
                    : trace.steps();
            result = new EndpointTrace(true, match.get(), steps, trace.terminals(),
                    trace.blocked(), trace.truncated(), null, null, List.of(), null);
        }
        return result.withRootInfo(success.resolvedCommit(), success.notices());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public EndpointTrace traceEndpoint(String method, String path, Integer maxDepth, Boolean terminalsOnly) {
        return traceEndpoint(method, path, maxDepth, terminalsOnly, null, null);
    }

    /**
     * Result payload of {@link #traceEndpoint}. {@code resolvedCommit}/{@code rootNotices}
     * are populated only via the root/ref-aware overload; {@code rootError} is set instead
     * of every other field when root/ref resolution itself failed (a bad root/ref is not
     * the same failure mode as "no indexed route matches", which stays in {@code error}).
     */
    public record EndpointTrace(
            boolean found,
            EndpointHandler handler,
            List<BeanIndex.TraceStep> steps,
            List<io.github.subnocte.springwiring.bean.BeanDefinition> terminals,
            List<BeanIndex.BlockedSite> blocked,
            boolean truncated,
            String error,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static EndpointTrace rootError(String reason) {
            return new EndpointTrace(false, null, List.of(), List.of(), List.of(), false, null,
                    null, List.of(), reason);
        }

        EndpointTrace withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new EndpointTrace(found, handler, steps, terminals, blocked, truncated, error,
                    resolvedCommit, rootNotices, rootError);
        }
    }

    /**
     * Result payload of {@link #resolveEndpoint}. {@code unresolvedCount} is always present;
     * the full {@code unresolvedMappings} list and {@code warning} are only populated on a
     * miss, where an unresolved mapping might be the endpoint the caller was looking for.
     * {@code rootError} is set instead of every other field when root/ref resolution itself
     * failed, which is distinct from a domain miss (no such endpoint).
     */
    public record EndpointResolution(
            boolean found,
            EndpointHandler match,
            List<EndpointHandler> suggestions,
            int unresolvedCount,
            List<UnresolvedMapping> unresolvedMappings,
            String warning,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static EndpointResolution found(EndpointHandler handler, int unresolvedCount) {
            return new EndpointResolution(true, handler, List.of(), unresolvedCount, List.of(), null,
                    null, List.of(), null);
        }

        static EndpointResolution notFound(List<EndpointHandler> suggestions, List<UnresolvedMapping> unresolved) {
            String warning = unresolved.isEmpty() ? null
                    : "No indexed route matched, but " + unresolved.size() + " mapping(s) could not be "
                    + "resolved statically; the requested endpoint may be among them (see unresolvedMappings).";
            return new EndpointResolution(false, null, suggestions, unresolved.size(), unresolved, warning,
                    null, List.of(), null);
        }

        static EndpointResolution rootError(String reason) {
            return new EndpointResolution(false, null, List.of(), 0, List.of(), null, null, List.of(), reason);
        }

        EndpointResolution withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new EndpointResolution(found, match, suggestions, unresolvedCount, unresolvedMappings, warning,
                    resolvedCommit, rootNotices, rootError);
        }
    }

    /**
     * Result payload of {@link #indexStatus}. {@code knownRoots} is always populated
     * (independent of whether this call's own root/ref resolved); {@code rootError} is set
     * instead of every coverage field when root/ref resolution itself failed.
     */
    public record IndexStatus(
            int endpointCount,
            int scannedFileCount,
            int unresolvedCount,
            Map<String, Long> unresolvedByReason,
            List<UnresolvedMapping> unresolvedMappings,
            int beanCount,
            Map<String, Long> unresolvedInjectionsByReason,
            int parseFailureCount,
            List<io.github.subnocte.springwiring.scanner.ParseFailure> parseFailures,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError,
            List<KnownRoot> knownRoots
    ) {
        static IndexStatus rootError(String reason, List<KnownRoot> knownRoots) {
            return new IndexStatus(0, 0, 0, Map.of(), List.of(), 0, Map.of(), 0, List.of(),
                    null, List.of(), reason, knownRoots);
        }
    }
}
