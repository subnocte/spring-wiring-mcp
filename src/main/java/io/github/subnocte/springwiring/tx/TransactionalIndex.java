package io.github.subnocte.springwiring.tx;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import io.github.subnocte.springwiring.scanner.ParsedSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private final Map<String, ClassTransactions> byFqcn;

    private TransactionalIndex(Map<String, ClassTransactions> byFqcn) {
        this.byFqcn = Map.copyOf(byFqcn);
    }

    /** Builds the index from an explicit list of source files (shared parsing front-end). */
    public static TransactionalIndex build(List<Path> sourceFiles) {
        Map<String, ClassTransactions> byFqcn = new HashMap<>();
        for (ParsedSources.ParsedSource unit : ParsedSources.parse(sourceFiles).units()) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface()) {
                    continue;
                }
                decl.getFullyQualifiedName().ifPresent(fqcn ->
                        byFqcn.put(fqcn, analyze(fqcn, decl, unit.path())));
            }
        }
        return new TransactionalIndex(byFqcn);
    }

    private static ClassTransactions analyze(String fqcn, ClassOrInterfaceDeclaration decl, Path path) {
        boolean classLevel = hasTransactional(decl.getAnnotations());

        List<MethodTx> methods = new ArrayList<>();
        Set<String> effectivelyTransactional = new HashSet<>();
        for (MethodDeclaration method : decl.getMethods()) {
            boolean own = hasTransactional(method.getAnnotations());
            boolean isPrivate = method.isPrivate();
            // Spring's proxy only intercepts external calls to non-private methods;
            // class-level @Transactional therefore covers non-private methods only.
            boolean effective = own ? !isPrivate : (classLevel && !isPrivate);
            String source = own ? "method" : (classLevel && !isPrivate ? "class" : "none");
            // The annotation itself is what the report shows for a private method:
            // transactional=true would claim behavior Spring never delivers.
            methods.add(new MethodTx(
                    method.getNameAsString(),
                    method.getBegin().map(p -> p.line).orElse(-1),
                    own || (classLevel && !isPrivate),
                    source,
                    own && isPrivate));
            if (effective || (own && isPrivate)) {
                effectivelyTransactional.add(method.getNameAsString());
            }
        }

        List<SelfInvocation> selfInvocations = new ArrayList<>();
        for (MethodDeclaration method : decl.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                boolean unqualified = call.getScope().isEmpty()
                        || call.getScope().get().isThisExpr();
                if (!unqualified) {
                    continue;
                }
                String callee = call.getNameAsString();
                if (effectivelyTransactional.contains(callee)
                        && !callee.equals(method.getNameAsString())) {
                    selfInvocations.add(new SelfInvocation(
                            method.getNameAsString(), callee,
                            call.getBegin().map(p -> p.line).orElse(-1)));
                }
            }
        }
        return new ClassTransactions(fqcn, path.toString(), classLevel,
                List.copyOf(methods), List.copyOf(selfInvocations));
    }

    private static boolean hasTransactional(List<? extends com.github.javaparser.ast.expr.AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(a -> a.getName().getIdentifier().equals("Transactional"));
    }

    /** Transactional facts for the class with this exact FQCN. */
    public Optional<ClassTransactions> of(String fqcn) {
        return Optional.ofNullable(byFqcn.get(fqcn));
    }

    /** FQCNs of scanned classes matching an exact FQCN or, failing that, a simple name. */
    public List<String> findClassByName(String className) {
        if (byFqcn.containsKey(className)) {
            return List.of(className);
        }
        String suffix = "." + className;
        return byFqcn.keySet().stream().filter(f -> f.endsWith(suffix)).sorted().toList();
    }
}
