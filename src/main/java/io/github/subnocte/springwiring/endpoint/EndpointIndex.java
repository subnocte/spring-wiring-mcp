package io.github.subnocte.springwiring.endpoint;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Static index of REST endpoints in a Spring Boot codebase: maps
 * {@code (HTTP method, path pattern)} to the handler method that implements it.
 *
 * <p>Built once from a set of {@code .java} source files via JavaParser. Source-only:
 * no compilation or classpath resolution is performed. Mappings that cannot be resolved
 * statically (constant-referenced paths, unsupported wildcard patterns, non-literal
 * expressions) are never indexed under a guessed value — they are collected as
 * {@link UnresolvedMapping} and exposed via {@link #unresolved()} so clients can see
 * exactly what the index does not cover.
 */
public final class EndpointIndex {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");

    private static final Set<String> METHOD_ATTR_NAMES = Set.of("value", "path");

    /** Mapping-annotation simple name -> fixed HTTP method, for the method-specific shorthand annotations. */
    private static final java.util.Map<String, String> SHORTHAND_MAPPINGS = java.util.Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    /** HTTP method used when a mapping annotation does not restrict the method. */
    public static final String ANY_METHOD = "ANY";

    private final List<EndpointHandler> handlers;
    private final List<UnresolvedMapping> unresolved;
    private final int scannedFileCount;

    private EndpointIndex(List<EndpointHandler> handlers, List<UnresolvedMapping> unresolved, int scannedFileCount) {
        this.handlers = List.copyOf(handlers);
        this.unresolved = List.copyOf(unresolved);
        this.scannedFileCount = scannedFileCount;
    }

    /** Scans {@code root} recursively and builds an index from every {@code .java} file found. */
    public static EndpointIndex forRoot(Path root) {
        return build(io.github.subnocte.springwiring.scanner.SourceScanner.scan(root));
    }

    /** Builds an index from an explicit list of source files. Files that fail to parse are skipped. */
    public static EndpointIndex build(List<Path> sourceFiles) {
        List<EndpointHandler> collected = new ArrayList<>();
        List<UnresolvedMapping> unresolvedCollected = new ArrayList<>();
        for (Path file : sourceFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                collectFromCompilationUnit(cu, file, collected, unresolvedCollected);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read source file: " + file, e);
            } catch (com.github.javaparser.ParseProblemException e) {
                // Skip unparsable files; a single malformed file should not abort the whole index.
            }
        }
        return new EndpointIndex(collected, unresolvedCollected, sourceFiles.size());
    }

    private static void collectFromCompilationUnit(
            CompilationUnit cu, Path file, List<EndpointHandler> out, List<UnresolvedMapping> unresolvedOut) {
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
                super.visit(decl, arg);
                if (!isController(decl)) {
                    return;
                }
                String fqcn = decl.getFullyQualifiedName().orElse(decl.getNameAsString());

                Optional<AnnotationExpr> classMapping = decl.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("RequestMapping"))
                        .findFirst();
                PathAttr baseAttr = classMapping.map(EndpointIndex::extractPathAttr)
                        .orElse(new PathAttr(List.of(""), null));
                if (baseAttr.nonLiteral() != null) {
                    unresolvedOut.add(new UnresolvedMapping(
                            file.toString(),
                            classMapping.flatMap(a -> a.getBegin()).map(p -> p.line).orElse(-1),
                            fqcn,
                            reasonFor(baseAttr.nonLiteral())));
                    if (baseAttr.literals().isEmpty()) {
                        // Base path unknown: indexing method paths would produce wrong patterns.
                        return;
                    }
                }

                for (MethodDeclaration method : decl.getMethods()) {
                    for (AnnotationExpr mapping : method.getAnnotations()) {
                        collectMethodMapping(mapping, method, fqcn, baseAttr.literals(), file, out, unresolvedOut);
                    }
                }
            }
        }, null);
    }

    private static void collectMethodMapping(
            AnnotationExpr mapping, MethodDeclaration method, String fqcn, List<String> basePaths,
            Path file, List<EndpointHandler> out, List<UnresolvedMapping> unresolvedOut) {
        String simpleName = mapping.getNameAsString();
        if (!SHORTHAND_MAPPINGS.containsKey(simpleName) && !simpleName.equals("RequestMapping")) {
            return;
        }
        String location = fqcn + "#" + method.getNameAsString();
        int line = method.getBegin().map(p -> p.line).orElse(-1);

        HttpMethodsAttr methodsAttr = resolveHttpMethods(mapping, simpleName);
        if (methodsAttr.nonLiteral() != null) {
            unresolvedOut.add(new UnresolvedMapping(
                    file.toString(), line, location, reasonFor(methodsAttr.nonLiteral())));
            return;
        }

        PathAttr pathAttr = extractPathAttr(mapping);
        if (pathAttr.nonLiteral() != null) {
            unresolvedOut.add(new UnresolvedMapping(
                    file.toString(), line, location, reasonFor(pathAttr.nonLiteral())));
            if (pathAttr.literals().isEmpty()) {
                return;
            }
        }

        for (String base : basePaths) {
            for (String methodPath : pathAttr.literals()) {
                String pattern = combine(base, methodPath);
                if (pattern.contains("*")) {
                    unresolvedOut.add(new UnresolvedMapping(
                            file.toString(), line, location, UnresolvedMapping.REASON_UNSUPPORTED_PATTERN));
                    continue;
                }
                for (String httpMethod : methodsAttr.methods()) {
                    out.add(new EndpointHandler(
                            httpMethod, pattern, fqcn, method.getNameAsString(), file.toString(), line));
                }
            }
        }
    }

    private static boolean isController(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(CONTROLLER_ANNOTATIONS::contains);
    }

    /** Classifies a non-literal attribute expression for unresolved reporting. */
    private static String reasonFor(Expression expr) {
        if (expr instanceof NameExpr || expr instanceof FieldAccessExpr) {
            return UnresolvedMapping.REASON_CONSTANT_REFERENCE;
        }
        return UnresolvedMapping.REASON_NON_LITERAL_EXPRESSION;
    }

    /**
     * Extracted HTTP methods of a mapping annotation. {@code nonLiteral} is non-null when a
     * {@code method} attribute was present but no value could be extracted from it.
     */
    private record HttpMethodsAttr(List<String> methods, Expression nonLiteral) {
    }

    private static HttpMethodsAttr resolveHttpMethods(AnnotationExpr mapping, String simpleName) {
        if (SHORTHAND_MAPPINGS.containsKey(simpleName)) {
            return new HttpMethodsAttr(List.of(SHORTHAND_MAPPINGS.get(simpleName)), null);
        }
        if (!(mapping instanceof NormalAnnotationExpr normal)) {
            return new HttpMethodsAttr(List.of(ANY_METHOD), null);
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("method")) {
                List<String> methods = expressionValues(pair.getValue(), EndpointIndex::fieldAccessName);
                if (methods.isEmpty()) {
                    return new HttpMethodsAttr(List.of(), firstExpression(pair.getValue()));
                }
                return new HttpMethodsAttr(methods, null);
            }
        }
        return new HttpMethodsAttr(List.of(ANY_METHOD), null);
    }

    private static String fieldAccessName(Expression expr) {
        return expr instanceof FieldAccessExpr fae ? fae.getNameAsString() : null;
    }

    /**
     * Extracted {@code value}/{@code path} attribute of a mapping annotation. {@code literals}
     * holds the string literals found ({@code [""]} when the attribute is absent);
     * {@code nonLiteral} is a sample of any component that could not be extracted.
     */
    private record PathAttr(List<String> literals, Expression nonLiteral) {
    }

    private static PathAttr extractPathAttr(AnnotationExpr anno) {
        Expression attrValue = null;
        if (anno instanceof SingleMemberAnnotationExpr single) {
            attrValue = single.getMemberValue();
        } else if (anno instanceof NormalAnnotationExpr normal) {
            attrValue = normal.getPairs().stream()
                    .filter(pair -> METHOD_ATTR_NAMES.contains(pair.getNameAsString()))
                    .map(MemberValuePair::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (attrValue == null) {
            return new PathAttr(List.of(""), null);
        }
        List<String> literals = expressionValues(attrValue, EndpointIndex::stringLiteralValue);
        Expression nonLiteral = firstNonLiteral(attrValue);
        if (literals.isEmpty() && nonLiteral == null) {
            return new PathAttr(List.of(""), null);
        }
        return new PathAttr(literals, nonLiteral);
    }

    private static String stringLiteralValue(Expression expr) {
        return expr instanceof StringLiteralExpr sle ? sle.asString() : null;
    }

    /** First component (array element or the expression itself) that is not a string literal; null if none. */
    private static Expression firstNonLiteral(Expression expr) {
        if (expr instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                if (!(e instanceof StringLiteralExpr)) {
                    return e;
                }
            }
            return null;
        }
        return expr instanceof StringLiteralExpr ? null : expr;
    }

    private static Expression firstExpression(Expression expr) {
        if (expr instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
            return array.getValues().get(0);
        }
        return expr;
    }

    private static List<String> expressionValues(Expression expr, java.util.function.Function<Expression, String> extractor) {
        List<String> result = new ArrayList<>();
        if (expr instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                String v = extractor.apply(e);
                if (v != null) {
                    result.add(v);
                }
            }
        } else {
            String v = extractor.apply(expr);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String combine(String base, String methodPath) {
        String b = normalize(base);
        String m = normalize(methodPath);
        String combined = b + m;
        return combined.isEmpty() ? "/" : combined;
    }

    /** All indexed endpoints, in source-scan order. */
    public List<EndpointHandler> all() {
        return handlers;
    }

    /** Mappings found in source but not statically resolvable; never silently dropped. */
    public List<UnresolvedMapping> unresolved() {
        return unresolved;
    }

    /** Number of {@code .java} files the index was built from. */
    public int scannedFileCount() {
        return scannedFileCount;
    }

    /**
     * Resolves an incoming request to its handler.
     *
     * @param httpMethod HTTP method, case-insensitive (e.g. {@code "GET"})
     * @param path       concrete request path (e.g. {@code "/users/42"})
     */
    public Optional<EndpointHandler> resolve(String httpMethod, String path) {
        String method = httpMethod.toUpperCase(java.util.Locale.ROOT);
        String requestPath = normalize(path);
        return handlers.stream()
                .filter(h -> methodMatches(h.httpMethod(), method))
                .filter(h -> pathMatches(h.pathPattern(), requestPath))
                // Spring picks the most specific of several matching patterns
                // (e.g. /owners/new beats /owners/{ownerId}); approximate that by
                // preferring the pattern with the fewest variable segments.
                .min(Comparator.comparingInt(h -> variableSegmentCount(h.pathPattern())));
    }

    private static int variableSegmentCount(String pattern) {
        int count = 0;
        for (String segment : splitSegments(pattern)) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                count++;
            }
        }
        return count;
    }

    /** Returns endpoints ranked by similarity to the given (method, path), for "did you mean" suggestions. */
    public List<EndpointHandler> suggestClosest(String httpMethod, String path, int limit) {
        String method = httpMethod.toUpperCase(java.util.Locale.ROOT);
        String requestPath = normalize(path);
        return handlers.stream()
                .sorted(Comparator.comparingInt(h -> suggestionScore(h, method, requestPath)))
                .limit(limit)
                .toList();
    }

    private static int suggestionScore(EndpointHandler handler, String method, String requestPath) {
        int methodPenalty = methodMatches(handler.httpMethod(), method) ? 0 : 1000;
        return methodPenalty + segmentDistance(splitSegments(handler.pathPattern()), splitSegments(requestPath));
    }

    private static boolean methodMatches(String indexedMethod, String requestedMethod) {
        return indexedMethod.equals(ANY_METHOD) || indexedMethod.equals(requestedMethod);
    }

    /** Matches a Spring-style path pattern (supporting {@code {var}} segments) against a concrete path. */
    static boolean pathMatches(String pattern, String path) {
        String[] patternSegments = splitSegments(pattern);
        String[] pathSegments = splitSegments(path);
        if (patternSegments.length != pathSegments.length) {
            return false;
        }
        for (int i = 0; i < patternSegments.length; i++) {
            String ps = patternSegments[i];
            boolean isVariable = ps.startsWith("{") && ps.endsWith("}");
            if (!isVariable && !ps.equals(pathSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] splitSegments(String path) {
        String trimmed = path.equals("/") ? "" : path;
        return trimmed.isEmpty() ? new String[0] : trimmed.substring(1).split("/");
    }

    /**
     * Edit distance between two path-segment sequences, treating a {@code {var}} pattern
     * segment as a free match against any concrete segment. This ranks near-misses (wrong
     * literal segment, missing/extra segment) sensibly for "did you mean" suggestions,
     * without letting brace characters in variable segments skew a plain character-level diff.
     */
    private static int segmentDistance(String[] patternSegments, String[] pathSegments) {
        int[] prev = new int[pathSegments.length + 1];
        int[] curr = new int[pathSegments.length + 1];
        for (int j = 0; j <= pathSegments.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= patternSegments.length; i++) {
            curr[0] = i;
            String ps = patternSegments[i - 1];
            boolean isVariable = ps.startsWith("{") && ps.endsWith("}");
            for (int j = 1; j <= pathSegments.length; j++) {
                int cost = (isVariable || ps.equals(pathSegments[j - 1])) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[pathSegments.length];
    }
}
