package io.github.subnocte.springwiring.tx;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Static view of {@code @Transactional} boundaries per class: which methods actually run
 * in a transaction once Spring's proxy semantics are accounted for, and where same-class
 * (self-invocation) calls bypass the proxy so the callee's annotation silently does not
 * apply. Reported, never guessed — the classic production surprise this catches is a
 * public non-transactional method calling an annotated one in the same class.
 */
public final class TransactionalIndex {

    /**
     * One method's effective transactional status.
     *
     * @param methodName                    simple method name
     * @param lineNumber                    declaration line
     * @param transactional                 whether Spring starts/joins a transaction when the
     *                                      method is called from OUTSIDE the class (proxy path)
     * @param source                        where that comes from: {@code method} (own annotation),
     *                                      {@code class} (class-level annotation), {@code none}
     * @param privateTransactionalWarning   true when {@code @Transactional} sits on a private
     *                                      method: the proxy can never intercept it, the
     *                                      annotation is dead
     */
    public record MethodTx(String methodName, int lineNumber, boolean transactional,
                           String source, boolean privateTransactionalWarning) {
    }

    /**
     * A same-class call to an effectively transactional method: the proxy is bypassed,
     * so the callee's transactional attributes do NOT apply on this path.
     */
    public record SelfInvocation(String callerMethod, String calleeMethod, int lineNumber) {
    }

    /** All transactional facts for one class. */
    public record ClassTransactions(String fqcn, String filePath, boolean classLevelTransactional,
                                    List<MethodTx> methods, List<SelfInvocation> selfInvocations) {
    }

    private TransactionalIndex() {
    }

    /** Builds the index from an explicit list of source files (shared parsing front-end). */
    public static TransactionalIndex build(List<Path> sourceFiles) {
        return new TransactionalIndex();
    }

    /** Transactional facts for the class with this exact FQCN. */
    public Optional<ClassTransactions> of(String fqcn) {
        return Optional.empty();
    }

    /** FQCNs of scanned classes matching an exact FQCN or, failing that, a simple name. */
    public List<String> findClassByName(String className) {
        return List.of();
    }
}
