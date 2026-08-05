package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import io.github.subnocte.springwiring.tx.TransactionalIndex;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * MCP tool surface for {@code @Transactional} boundary analysis.
 */
@Service
public class TransactionTools {

    private final CodeIndexes codeIndexes;

    public TransactionTools(CodeIndexes codeIndexes) {
        this.codeIndexes = codeIndexes;
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
            String className
    ) {
        TransactionalIndex index = codeIndexes.current().transactionalIndex();
        List<String> matches = index.findClassByName(className);
        if (matches.isEmpty()) {
            return new TransactionalBoundariesResult(false, null, List.of(),
                    "No scanned class named '" + className + "'.");
        }
        if (matches.size() > 1) {
            return new TransactionalBoundariesResult(false, null, matches,
                    "Simple name matches " + matches.size() + " classes; retry with a fully qualified name.");
        }
        Optional<TransactionalIndex.ClassTransactions> transactions = index.of(matches.get(0));
        return transactions
                .map(t -> new TransactionalBoundariesResult(true, t, List.of(), null))
                .orElseGet(() -> new TransactionalBoundariesResult(false, null, List.of(),
                        "No scanned class named '" + className + "'."));
    }

    /** Result payload of {@link #transactionalBoundaries}. */
    public record TransactionalBoundariesResult(
            boolean found,
            TransactionalIndex.ClassTransactions transactions,
            List<String> matches,
            String error
    ) {
    }
}
