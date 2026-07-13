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
 * no compilation or classpath resolution is performed.
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

    private EndpointIndex(List<EndpointHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /** Scans {@code root} recursively and builds an index from every {@code .java} file found. */
    public static EndpointIndex forRoot(Path root) {
        return build(io.github.subnocte.springwiring.scanner.SourceScanner.scan(root));
    }

    /** Builds an index from an explicit list of source files. Files that fail to parse are skipped. */
    public static EndpointIndex build(List<Path> sourceFiles) {
        List<EndpointHandler> collected = new ArrayList<>();
        for (Path file : sourceFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                collectFromCompilationUnit(cu, file, collected);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read source file: " + file, e);
            } catch (com.github.javaparser.ParseProblemException e) {
                // Skip unparsable files; a single malformed file should not abort the whole index.
            }
        }
        return new EndpointIndex(collected);
    }

    private static void collectFromCompilationUnit(CompilationUnit cu, Path file, List<EndpointHandler> out) {
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
                super.visit(decl, arg);
                if (!isController(decl)) {
                    return;
                }
                String fqcn = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                List<String> basePaths = classLevelPaths(decl);

                for (MethodDeclaration method : decl.getMethods()) {
                    for (AnnotationExpr mapping : method.getAnnotations()) {
                        String simpleName = mapping.getNameAsString();
                        List<String> httpMethods = resolveHttpMethods(mapping, simpleName);
                        if (httpMethods.isEmpty()) {
                            continue;
                        }
                        List<String> methodPaths = extractPaths(mapping);
                        int line = method.getBegin().map(p -> p.line).orElse(-1);
                        for (String base : basePaths) {
                            for (String methodPath : methodPaths) {
                                String pattern = combine(base, methodPath);
                                for (String httpMethod : httpMethods) {
                                    out.add(new EndpointHandler(
                                            httpMethod, pattern, fqcn, method.getNameAsString(),
                                            file.toString(), line));
                                }
                            }
                        }
                    }
                }
            }
        }, null);
    }

    private static boolean isController(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(CONTROLLER_ANNOTATIONS::contains);
    }

    private static List<String> classLevelPaths(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .findFirst()
                .map(EndpointIndex::extractPaths)
                .orElse(List.of(""));
    }

    /** Determines which HTTP methods a mapping annotation applies to; empty if it is not a mapping annotation. */
    private static List<String> resolveHttpMethods(AnnotationExpr mapping, String simpleName) {
        if (SHORTHAND_MAPPINGS.containsKey(simpleName)) {
            return List.of(SHORTHAND_MAPPINGS.get(simpleName));
        }
        if (!simpleName.equals("RequestMapping")) {
            return List.of();
        }
        if (!(mapping instanceof NormalAnnotationExpr normal)) {
            return List.of(ANY_METHOD);
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("method")) {
                List<String> methods = expressionValues(pair.getValue(), EndpointIndex::fieldAccessName);
                return methods.isEmpty() ? List.of(ANY_METHOD) : methods;
            }
        }
        return List.of(ANY_METHOD);
    }

    private static String fieldAccessName(Expression expr) {
        return expr instanceof FieldAccessExpr fae ? fae.getNameAsString() : null;
    }

    /** Extracts {@code value}/{@code path} attribute of a mapping annotation; {@code [""]} when absent. */
    private static List<String> extractPaths(AnnotationExpr anno) {
        if (anno instanceof SingleMemberAnnotationExpr single) {
            List<String> values = expressionValues(single.getMemberValue(), EndpointIndex::stringLiteralValue);
            return values.isEmpty() ? List.of("") : values;
        }
        if (anno instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (METHOD_ATTR_NAMES.contains(pair.getNameAsString())) {
                    List<String> values = expressionValues(pair.getValue(), EndpointIndex::stringLiteralValue);
                    if (!values.isEmpty()) {
                        return values;
                    }
                }
            }
            return List.of("");
        }
        return List.of("");
    }

    private static String stringLiteralValue(Expression expr) {
        return expr instanceof StringLiteralExpr sle ? sle.asString() : null;
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
                .findFirst();
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
