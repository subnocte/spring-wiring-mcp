package io.github.subnocte.springwiring.endpoint;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
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
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Static index of REST endpoints in a Spring Boot codebase: maps
 * {@code (HTTP method, path pattern)} to the handler method that implements it.
 *
 * <p>Built once from a set of {@code .java} source files via JavaParser. Source-only:
 * no compilation or classpath resolution is performed. Mappings that cannot be resolved
 * statically are never indexed under a guessed value — they are collected as
 * {@link UnresolvedMapping} and exposed via {@link #unresolved()} so clients can see
 * exactly what the index does not cover.
 *
 * <p>Controllers whose mappings live on an implemented interface are supported when the
 * interface source is under the scanned root; per Spring semantics, annotations on the
 * implementation take precedence over the interface's (class-level prefixes are not
 * concatenated). Controllers implementing interfaces that are not in the scanned sources
 * (e.g. build-time generated API interfaces) are self-reported as unresolved.
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

    /** A parsed source file. */
    private record ParsedUnit(CompilationUnit cu, Path path) {
    }

    /** A type declaration found in the scanned sources, with its surrounding context. */
    private record TypeEntry(ClassOrInterfaceDeclaration decl, CompilationUnit cu, Path path) {
    }

    /** Builds an index from an explicit list of source files. Files that fail to parse are skipped. */
    public static EndpointIndex build(List<Path> sourceFiles) {
        List<ParsedUnit> units = new ArrayList<>();
        for (Path file : sourceFiles) {
            try {
                units.add(new ParsedUnit(StaticJavaParser.parse(file), file));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read source file: " + file, e);
            } catch (com.github.javaparser.ParseProblemException e) {
                // Skip unparsable files; a single malformed file should not abort the whole index.
            }
        }

        Map<String, TypeEntry> typeTable = new HashMap<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                decl.getFullyQualifiedName().ifPresent(
                        fqcn -> typeTable.put(fqcn, new TypeEntry(decl, unit.cu(), unit.path())));
            }
        }

        Map<String, String> constants = new HashMap<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<String> owner = decl.getFullyQualifiedName();
                if (owner.isEmpty()) {
                    continue;
                }
                for (FieldDeclaration field : decl.getFields()) {
                    // interface fields are implicitly public static final
                    boolean constant = decl.isInterface() || (field.isStatic() && field.isFinal());
                    if (!constant) {
                        continue;
                    }
                    field.getVariables().forEach(variable -> {
                        if (variable.getTypeAsString().equals("String")
                                && variable.getInitializer().orElse(null) instanceof StringLiteralExpr literal) {
                            constants.put(owner.get() + "#" + variable.getNameAsString(), literal.asString());
                        }
                    });
                }
            }
        }

        AnalysisContext ctx = new AnalysisContext(typeTable, constants);
        List<EndpointHandler> collected = new ArrayList<>();
        List<UnresolvedMapping> unresolvedCollected = new ArrayList<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface() && isController(decl)) {
                    collectController(decl, unit, ctx, collected, unresolvedCollected);
                }
            }
        }
        return new EndpointIndex(collected, unresolvedCollected, sourceFiles.size());
    }

    /** Shared lookup tables for one build pass. */
    private record AnalysisContext(Map<String, TypeEntry> typeTable, Map<String, String> constants) {
    }

    /**
     * Resolves a mapping-attribute expression to a string: a literal directly, or a
     * {@code static final String} constant reachable from {@code enclosing}/{@code cu}
     * (same class, qualified field access via imports, or static import).
     */
    private static String stringValueOf(
            Expression expr, ClassOrInterfaceDeclaration enclosing, CompilationUnit cu, AnalysisContext ctx) {
        if (expr instanceof StringLiteralExpr sle) {
            return sle.asString();
        }
        if (expr instanceof NameExpr name) {
            String simple = name.getNameAsString();
            String ownerFqcn = enclosing.getFullyQualifiedName().orElse(null);
            if (ownerFqcn != null) {
                String value = ctx.constants().get(ownerFqcn + "#" + simple);
                if (value != null) {
                    return value;
                }
            }
            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isStatic()) {
                    continue;
                }
                if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + simple)) {
                    String owner = imp.getNameAsString()
                            .substring(0, imp.getNameAsString().length() - simple.length() - 1);
                    String value = ctx.constants().get(owner + "#" + simple);
                    if (value != null) {
                        return value;
                    }
                } else if (imp.isAsterisk()) {
                    String value = ctx.constants().get(imp.getNameAsString() + "#" + simple);
                    if (value != null) {
                        return value;
                    }
                }
            }
            return null;
        }
        if (expr instanceof FieldAccessExpr access) {
            String field = access.getNameAsString();
            String scope = access.getScope().toString();
            String direct = ctx.constants().get(scope + "#" + field);
            if (direct != null) {
                return direct;
            }
            return resolveType(scope, cu, ctx.typeTable())
                    .flatMap(t -> t.decl().getFullyQualifiedName())
                    .map(f -> ctx.constants().get(f + "#" + field))
                    .orElse(null);
        }
        return null;
    }

    private static void collectController(
            ClassOrInterfaceDeclaration decl, ParsedUnit unit, AnalysisContext ctx,
            List<EndpointHandler> out, List<UnresolvedMapping> unresolvedOut) {
        String fqcn = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
        Path file = unit.path();
        java.util.function.Function<Expression, String> valueOf =
                expr -> stringValueOf(expr, decl, unit.cu(), ctx);

        Optional<AnnotationExpr> classMapping = requestMappingOf(decl);
        PathAttr baseAttr = classMapping.map(a -> extractPathAttr(a, valueOf))
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

        boolean anyMethodMapping = false;
        for (MethodDeclaration method : decl.getMethods()) {
            for (AnnotationExpr mapping : method.getAnnotations()) {
                if (isMappingAnnotation(mapping.getNameAsString())) {
                    anyMethodMapping = true;
                    int line = method.getBegin().map(p -> p.line).orElse(-1);
                    collectMethodMapping(mapping, method, fqcn, baseAttr.literals(),
                            file, line, valueOf, out, unresolvedOut);
                }
            }
        }

        if (!anyMethodMapping && !decl.getImplementedTypes().isEmpty()) {
            collectFromInterfaces(decl, fqcn, classMapping.isPresent(), baseAttr.literals(),
                    unit, ctx, out, unresolvedOut);
        }
    }

    /**
     * Indexes mappings declared on implemented interfaces (Spring inherits them when the
     * implementation declares none). The implementation's class-level {@code @RequestMapping}
     * takes precedence over the interface's; they are not concatenated.
     */
    private static void collectFromInterfaces(
            ClassOrInterfaceDeclaration decl, String fqcn, boolean implHasClassMapping,
            List<String> implBasePaths, ParsedUnit unit, AnalysisContext ctx,
            List<EndpointHandler> out, List<UnresolvedMapping> unresolvedOut) {
        boolean anyIndexed = false;
        boolean anyInterfaceMissing = false;

        for (ClassOrInterfaceType implemented : decl.getImplementedTypes()) {
            Optional<TypeEntry> ifaceEntry = resolveType(implemented.getNameWithScope(), unit.cu(), ctx.typeTable());
            if (ifaceEntry.isEmpty() || !ifaceEntry.get().decl().isInterface()) {
                anyInterfaceMissing = true;
                continue;
            }
            ClassOrInterfaceDeclaration iface = ifaceEntry.get().decl();
            java.util.function.Function<Expression, String> ifaceValueOf =
                    expr -> stringValueOf(expr, iface, ifaceEntry.get().cu(), ctx);

            List<String> basePaths = implBasePaths;
            if (!implHasClassMapping) {
                PathAttr ifaceBase = requestMappingOf(iface).map(a -> extractPathAttr(a, ifaceValueOf))
                        .orElse(new PathAttr(List.of(""), null));
                if (ifaceBase.nonLiteral() != null && ifaceBase.literals().isEmpty()) {
                    unresolvedOut.add(new UnresolvedMapping(
                            ifaceEntry.get().path().toString(),
                            requestMappingOf(iface).flatMap(a -> a.getBegin()).map(p -> p.line).orElse(-1),
                            fqcn,
                            reasonFor(ifaceBase.nonLiteral())));
                    continue;
                }
                basePaths = ifaceBase.literals();
            }

            for (MethodDeclaration ifaceMethod : iface.getMethods()) {
                for (AnnotationExpr mapping : ifaceMethod.getAnnotations()) {
                    if (!isMappingAnnotation(mapping.getNameAsString())) {
                        continue;
                    }
                    // Point the handler at the implementing method when it exists,
                    // otherwise at the interface (default methods).
                    Path handlerFile = ifaceEntry.get().path();
                    int handlerLine = ifaceMethod.getBegin().map(p -> p.line).orElse(-1);
                    List<MethodDeclaration> impls = decl.getMethodsByName(ifaceMethod.getNameAsString());
                    if (!impls.isEmpty()) {
                        handlerFile = unit.path();
                        handlerLine = impls.get(0).getBegin().map(p -> p.line).orElse(-1);
                    }
                    int before = out.size();
                    collectMethodMapping(mapping, ifaceMethod, fqcn, basePaths,
                            handlerFile, handlerLine, ifaceValueOf, out, unresolvedOut);
                    if (out.size() > before) {
                        anyIndexed = true;
                    }
                }
            }
        }

        if (!anyIndexed && anyInterfaceMissing) {
            unresolvedOut.add(new UnresolvedMapping(
                    unit.path().toString(),
                    decl.getBegin().map(p -> p.line).orElse(-1),
                    fqcn,
                    UnresolvedMapping.REASON_INTERFACE_MAPPINGS_NOT_FOUND));
        }
    }

    /** Resolves a type name used in {@code cu} to a scanned type declaration. */
    private static Optional<TypeEntry> resolveType(String name, CompilationUnit cu, Map<String, TypeEntry> typeTable) {
        if (name.contains(".")) {
            return Optional.ofNullable(typeTable.get(name));
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + name)) {
                return Optional.ofNullable(typeTable.get(imp.getNameAsString()));
            }
        }
        String samePackage = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString() + "." + name)
                .orElse(name);
        TypeEntry entry = typeTable.get(samePackage);
        if (entry != null) {
            return Optional.of(entry);
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic() && imp.isAsterisk()) {
                entry = typeTable.get(imp.getNameAsString() + "." + name);
                if (entry != null) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isMappingAnnotation(String simpleName) {
        return SHORTHAND_MAPPINGS.containsKey(simpleName) || simpleName.equals("RequestMapping");
    }

    private static Optional<AnnotationExpr> requestMappingOf(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .findFirst();
    }

    private static void collectMethodMapping(
            AnnotationExpr mapping, MethodDeclaration method, String fqcn, List<String> basePaths,
            Path handlerFile, int handlerLine, java.util.function.Function<Expression, String> valueOf,
            List<EndpointHandler> out, List<UnresolvedMapping> unresolvedOut) {
        String simpleName = mapping.getNameAsString();
        String location = fqcn + "#" + method.getNameAsString();

        HttpMethodsAttr methodsAttr = resolveHttpMethods(mapping, simpleName);
        if (methodsAttr.nonLiteral() != null) {
            unresolvedOut.add(new UnresolvedMapping(
                    handlerFile.toString(), handlerLine, location, reasonFor(methodsAttr.nonLiteral())));
            return;
        }

        PathAttr pathAttr = extractPathAttr(mapping, valueOf);
        if (pathAttr.nonLiteral() != null) {
            unresolvedOut.add(new UnresolvedMapping(
                    handlerFile.toString(), handlerLine, location, reasonFor(pathAttr.nonLiteral())));
            if (pathAttr.literals().isEmpty()) {
                return;
            }
        }

        for (String base : basePaths) {
            for (String methodPath : pathAttr.literals()) {
                String pattern = combine(base, methodPath);
                if (pattern.contains("*")) {
                    unresolvedOut.add(new UnresolvedMapping(
                            handlerFile.toString(), handlerLine, location,
                            UnresolvedMapping.REASON_UNSUPPORTED_PATTERN));
                    continue;
                }
                for (String httpMethod : methodsAttr.methods()) {
                    out.add(new EndpointHandler(
                            httpMethod, pattern, fqcn, method.getNameAsString(),
                            handlerFile.toString(), handlerLine));
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

    private static PathAttr extractPathAttr(AnnotationExpr anno, java.util.function.Function<Expression, String> valueOf) {
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
        List<String> literals = expressionValues(attrValue, valueOf);
        Expression nonLiteral = firstUnextractable(attrValue, valueOf);
        if (literals.isEmpty() && nonLiteral == null) {
            return new PathAttr(List.of(""), null);
        }
        return new PathAttr(literals, nonLiteral);
    }

    /** First component (array element or the expression itself) the extractor cannot evaluate; null if none. */
    private static Expression firstUnextractable(Expression expr, java.util.function.Function<Expression, String> valueOf) {
        if (expr instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                if (valueOf.apply(e) == null) {
                    return e;
                }
            }
            return null;
        }
        return valueOf.apply(expr) == null ? expr : null;
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
