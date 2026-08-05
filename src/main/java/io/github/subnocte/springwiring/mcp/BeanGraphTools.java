package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanDefinition;
import io.github.subnocte.springwiring.bean.BeanDependencies;
import io.github.subnocte.springwiring.bean.BeanEdge;
import io.github.subnocte.springwiring.bean.BeanIndex;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * MCP tool surface for the bean dependency graph.
 */
@Service
public class BeanGraphTools {

    private final BeanIndex beanIndex;

    public BeanGraphTools(BeanIndex beanIndex) {
        this.beanIndex = beanIndex;
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
            String className
    ) {
        List<BeanDefinition> matches = beanIndex.findByName(className);
        if (matches.isEmpty()) {
            return BeanDependencyResult.notFound(
                    "No scanned bean named '" + className + "'. It may be a library bean, "
                            + "a non-bean class, or outside CODE_ROOT.");
        }
        if (matches.size() > 1) {
            return BeanDependencyResult.ambiguous(matches);
        }
        Optional<BeanDependencies> deps = beanIndex.dependenciesOf(matches.get(0).fqcn());
        return deps.map(BeanDependencyResult::found)
                .orElseGet(() -> BeanDependencyResult.notFound(
                        "Bean '" + matches.get(0).fqcn() + "' has no analyzed dependencies."));
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
            String className
    ) {
        List<String> matches = beanIndex.findTypeByName(className);
        if (matches.isEmpty()) {
            return new BeanDependentsResult(false, null, List.of(), List.of(),
                    "No scanned type named '" + className + "' is referenced by any bean. It may be "
                            + "a library type, unused, or outside CODE_ROOT.");
        }
        if (matches.size() > 1) {
            return new BeanDependentsResult(false, null, List.of(), matches,
                    "Simple name matches " + matches.size() + " types; retry with a fully qualified name.");
        }
        return new BeanDependentsResult(true, matches.get(0),
                beanIndex.dependentsOf(matches.get(0)), List.of(), null);
    }

    /**
     * Result payload of {@link #beanDependents}. On an ambiguous simple name,
     * {@code matches} lists the FQCNs to retry with.
     */
    public record BeanDependentsResult(
            boolean found,
            String targetFqcn,
            List<BeanIndex.Dependent> dependents,
            List<String> matches,
            String error
    ) {
    }

    /**
     * Result payload of {@link #beanDependencies}. On an ambiguous simple name,
     * {@code matches} lists the FQCNs to retry with.
     */
    public record BeanDependencyResult(
            boolean found,
            BeanDefinition bean,
            List<BeanEdge> edges,
            List<BeanDefinition> matches,
            String error
    ) {
        static BeanDependencyResult found(BeanDependencies deps) {
            return new BeanDependencyResult(true, deps.bean(), deps.edges(), List.of(), null);
        }

        static BeanDependencyResult notFound(String error) {
            return new BeanDependencyResult(false, null, List.of(), List.of(), error);
        }

        static BeanDependencyResult ambiguous(List<BeanDefinition> matches) {
            return new BeanDependencyResult(false, null, List.of(), matches,
                    "Simple name matches " + matches.size() + " beans; retry with a fully qualified name.");
        }
    }
}
