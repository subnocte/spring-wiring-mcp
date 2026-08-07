package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanDefinition;
import io.github.subnocte.springwiring.bean.BeanDependencies;
import io.github.subnocte.springwiring.bean.BeanEdge;
import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.index.CodeIndexes;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * MCP tool surface for the bean dependency graph.
 */
@Service
public class BeanGraphTools {

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
    public BeanGraphTools(CodeIndexes codeIndexes) {
        this(RootRefResolver.fixed(codeIndexes));
    }

    @Autowired
    public BeanGraphTools(RootRefResolver resolver) {
        this.resolver = resolver;
    }

    @McpTool(
            name = "beanDependencies",
            description = "Lists the Spring beans a given bean depends on, resolved to the concrete "
                    + "implementation that wins at injection time, with the source file and line of each. "
                    + "Dependencies are modeled as the bean's instance fields, which covers field @Autowired, "
                    + "Lombok @AllArgsConstructor/@RequiredArgsConstructor, and hand-written constructor "
                    + "injection alike (a constructor parameter never stored in a field is not seen). "
                    + "Interfaces resolve via single-implementation, @Primary, or field @Qualifier; MyBatis "
                    + "@Mapper and Spring Data repository interfaces are terminal beans. Sites that cannot "
                    + "be decided statically (multiple candidates, @Profile/@ConditionalOn candidates, "
                    + "List/Map injection, no implementation in the scanned sources) are reported with a "
                    + "reason and the candidate list instead of being guessed.",
            // Pure lookup against an in-memory index built from local sources: safe to
            // call freely, safe to retry, and touches nothing outside the indexed codebase.
            annotations = @McpTool.McpAnnotations(
                    title = "Resolve Spring bean dependencies",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public BeanDependencyResult beanDependencies(
            @McpToolParam(description = "Bean class: fully qualified name, or a simple name if unique",
                    required = true)
            String className,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return BeanDependencyResult.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        BeanIndex beanIndex = success.indexes().current().beanIndex();
        List<BeanDefinition> matches = beanIndex.findByName(className);
        BeanDependencyResult result;
        if (matches.isEmpty()) {
            result = BeanDependencyResult.notFound(
                    "No scanned bean named '" + className + "'. It may be a library bean, "
                            + "a non-bean class, or outside CODE_ROOT.");
        } else if (matches.size() > 1) {
            result = BeanDependencyResult.ambiguous(matches);
        } else {
            Optional<BeanDependencies> deps = beanIndex.dependenciesOf(matches.get(0).fqcn());
            result = deps.map(BeanDependencyResult::found)
                    .orElseGet(() -> BeanDependencyResult.notFound(
                            "Bean '" + matches.get(0).fqcn() + "' has no analyzed dependencies."));
        }
        return result.withRootInfo(success.resolvedCommit(), success.notices());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public BeanDependencyResult beanDependencies(String className) {
        return beanDependencies(className, null, null);
    }

    @McpTool(
            name = "beanDependents",
            description = "Reverse lookup: lists the Spring beans that depend on a given class or "
                    + "interface, with the injecting field and line. Reports certain dependents "
                    + "(sites resolved to it or declared as it) and possible dependents (unresolved "
                    + "sites listing it as a candidate) — possible ones are included rather than "
                    + "hidden, marked via=candidate. Useful for impact analysis before changing a "
                    + "bean or its contract.",
            // Pure lookup against an in-memory index built from local sources: safe to
            // call freely, safe to retry, and touches nothing outside the indexed codebase.
            annotations = @McpTool.McpAnnotations(
                    title = "Find dependents of a Spring bean",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public BeanDependentsResult beanDependents(
            @McpToolParam(description = "Bean class or interface: fully qualified name, or a simple "
                    + "name if unique", required = true)
            String className,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return BeanDependentsResult.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        BeanIndex beanIndex = success.indexes().current().beanIndex();
        List<String> matches = beanIndex.findTypeByName(className);
        BeanDependentsResult result;
        if (matches.isEmpty()) {
            result = new BeanDependentsResult(false, null, List.of(), List.of(),
                    "No scanned type named '" + className + "' is referenced by any bean. It may be "
                            + "a library type, unused, or outside CODE_ROOT.",
                    null, List.of(), null);
        } else if (matches.size() > 1) {
            result = new BeanDependentsResult(false, null, List.of(), matches,
                    "Simple name matches " + matches.size() + " types; retry with a fully qualified name.",
                    null, List.of(), null);
        } else {
            result = new BeanDependentsResult(true, matches.get(0),
                    beanIndex.dependentsOf(matches.get(0)), List.of(), null, null, List.of(), null);
        }
        return result.withRootInfo(success.resolvedCommit(), success.notices());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public BeanDependentsResult beanDependents(String className) {
        return beanDependents(className, null, null);
    }

    /**
     * Result payload of {@link #beanDependents}. On an ambiguous simple name,
     * {@code matches} lists the FQCNs to retry with. {@code rootError} is set instead of
     * every other field when root/ref resolution itself failed.
     */
    public record BeanDependentsResult(
            boolean found,
            String targetFqcn,
            List<BeanIndex.Dependent> dependents,
            List<String> matches,
            String error,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static BeanDependentsResult rootError(String reason) {
            return new BeanDependentsResult(false, null, List.of(), List.of(), null, null, List.of(), reason);
        }

        BeanDependentsResult withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new BeanDependentsResult(found, targetFqcn, dependents, matches, error,
                    resolvedCommit, rootNotices, rootError);
        }
    }

    /**
     * Result payload of {@link #beanDependencies}. On an ambiguous simple name,
     * {@code matches} lists the FQCNs to retry with. {@code rootError} is set instead of
     * every other field when root/ref resolution itself failed.
     */
    public record BeanDependencyResult(
            boolean found,
            BeanDefinition bean,
            List<BeanEdge> edges,
            List<BeanDefinition> matches,
            String error,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static BeanDependencyResult found(BeanDependencies deps) {
            return new BeanDependencyResult(true, deps.bean(), deps.edges(), List.of(), null,
                    null, List.of(), null);
        }

        static BeanDependencyResult notFound(String error) {
            return new BeanDependencyResult(false, null, List.of(), List.of(), error, null, List.of(), null);
        }

        static BeanDependencyResult ambiguous(List<BeanDefinition> matches) {
            return new BeanDependencyResult(false, null, List.of(), matches,
                    "Simple name matches " + matches.size() + " beans; retry with a fully qualified name.",
                    null, List.of(), null);
        }

        static BeanDependencyResult rootError(String reason) {
            return new BeanDependencyResult(false, null, List.of(), List.of(), null, null, List.of(), reason);
        }

        BeanDependencyResult withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new BeanDependencyResult(found, bean, edges, matches, error,
                    resolvedCommit, rootNotices, rootError);
        }
    }
}
