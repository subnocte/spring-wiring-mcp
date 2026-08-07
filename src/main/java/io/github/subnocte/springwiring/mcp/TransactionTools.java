package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import io.github.subnocte.springwiring.tx.TransactionalIndex;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * MCP tool surface for {@code @Transactional} boundary analysis.
 */
@Service
public class TransactionTools {

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
    public TransactionTools(CodeIndexes codeIndexes) {
        this(RootRefResolver.fixed(codeIndexes));
    }

    @Autowired
    public TransactionTools(RootRefResolver resolver) {
        this.resolver = resolver;
    }

    @McpTool(
            name = "transactionalBoundaries",
            description = "Shows where transactions actually start and end for one class once Spring's "
                    + "proxy semantics are accounted for: each method's effective transactional status "
                    + "when called from outside (own annotation, class-level annotation, or none), "
                    + "same-class calls to @Transactional methods where the proxy is bypassed and the "
                    + "callee's annotation silently does not apply (self-invocation), and @Transactional "
                    + "on private methods, which the proxy can never intercept.",
            annotations = @McpTool.McpAnnotations(
                    title = "Transactional boundaries of a class",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public TransactionalBoundariesResult transactionalBoundaries(
            @McpToolParam(description = "Class to analyze: fully qualified name, or a simple name if unique",
                    required = true)
            String className,
            @McpToolParam(description = ROOT_PARAM_DESCRIPTION, required = false)
            String root,
            @McpToolParam(description = REF_PARAM_DESCRIPTION, required = false)
            String ref
    ) {
        RootRefResolver.Result resolution = resolver.resolve(root, ref);
        if (resolution instanceof RootRefResolver.Failure failure) {
            return TransactionalBoundariesResult.rootError(failure.reason());
        }
        RootRefResolver.Success success = (RootRefResolver.Success) resolution;
        TransactionalIndex index = success.indexes().current().transactionalIndex();
        List<String> matches = index.findClassByName(className);
        TransactionalBoundariesResult result;
        if (matches.isEmpty()) {
            result = new TransactionalBoundariesResult(false, null, List.of(),
                    "No scanned class named '" + className + "'.", null, List.of(), null);
        } else if (matches.size() > 1) {
            result = new TransactionalBoundariesResult(false, null, matches,
                    "Simple name matches " + matches.size() + " classes; retry with a fully qualified name.",
                    null, List.of(), null);
        } else {
            Optional<TransactionalIndex.ClassTransactions> transactions = index.of(matches.get(0));
            result = transactions
                    .map(t -> new TransactionalBoundariesResult(true, t, List.of(), null, null, List.of(), null))
                    .orElseGet(() -> new TransactionalBoundariesResult(false, null, List.of(),
                            "No scanned class named '" + className + "'.", null, List.of(), null));
        }
        return result.withRootInfo(success.resolvedCommit(), success.notices());
    }

    /** Legacy overload: root and ref both omitted, identical to pre-root/ref behavior. */
    public TransactionalBoundariesResult transactionalBoundaries(String className) {
        return transactionalBoundaries(className, null, null);
    }

    /**
     * Result payload of {@link #transactionalBoundaries}. {@code rootError} is set instead
     * of every other field when root/ref resolution itself failed.
     */
    public record TransactionalBoundariesResult(
            boolean found,
            TransactionalIndex.ClassTransactions transactions,
            List<String> matches,
            String error,
            ResolvedCommit resolvedCommit,
            List<String> rootNotices,
            String rootError
    ) {
        static TransactionalBoundariesResult rootError(String reason) {
            return new TransactionalBoundariesResult(false, null, List.of(), null, null, List.of(), reason);
        }

        TransactionalBoundariesResult withRootInfo(ResolvedCommit resolvedCommit, List<String> rootNotices) {
            return new TransactionalBoundariesResult(found, transactions, matches, error,
                    resolvedCommit, rootNotices, rootError);
        }
    }
}
