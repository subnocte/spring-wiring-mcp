package io.github.subnocte.springwiring.testwiring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import io.github.subnocte.springwiring.scanner.ParsedSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Static view of a JUnit5 test class's "test-side wiring": which types it declares as real
 * (constructed, not doubled), which it declares as test doubles (and what kind), which
 * types are frozen via {@code mockStatic}, and what Spring test slice (if any) it runs
 * under. This is a declaration-level analysis, not a runtime one — see the class-level
 * non-goals below for what it deliberately does not attempt.
 *
 * <p>Scope, v1:
 * <ul>
 *   <li>Field-only: a {@code mock(X.class)} assigned to a local variable is invisible.
 *   <li>JUnit5 only: JUnit4/TestNG constructs are not recognized (detecting them and
 *       reporting "unsupported" is out of scope too — they simply produce no wiring).
 *   <li>{@code @Configuration}/{@code @TestConfiguration} bean overrides are not resolved.
 *   <li>Nothing about whether a Spring context actually starts or which beans it would
 *       wire is modeled — this is the analysis of what the test source <em>declares</em>,
 *       never a claim about what runs.
 * </ul>
 *
 * <p>Resolution follows the same contract as every other index in this project: a
 * declaration that cannot be resolved statically (an unrecognized annotation import, a
 * {@code mockStatic} argument that isn't a class literal) is reported in {@link
 * TestClassWiring#unresolved()} with a reason, never guessed.
 */
public final class TestWiringIndex {

    /** Known Mockito/Spring test-double annotations, simple name to the one FQCN that counts. */
    private static final Map<String, String> KNOWN_MOCK_ANNOTATION_FQCNS = Map.of(
            "Mock", "org.mockito.Mock",
            "Spy", "org.mockito.Spy",
            "InjectMocks", "org.mockito.InjectMocks",
            "MockBean", "org.springframework.boot.test.mock.mockito.MockBean",
            "SpyBean", "org.springframework.boot.test.mock.mockito.SpyBean",
            "MockitoBean", "org.springframework.test.context.bean.override.mockito.MockitoBean",
            "MockitoSpyBean", "org.springframework.test.context.bean.override.mockito.MockitoSpyBean");

    /** {@link MockKind} for every known annotation except {@code @InjectMocks} (a real subject, not a double). */
    private static final Map<String, MockKind> MOCK_KIND_BY_ANNOTATION = Map.of(
            "Mock", MockKind.MOCK,
            "Spy", MockKind.SPY,
            "MockBean", MockKind.MOCK_BEAN,
            "SpyBean", MockKind.SPY_BEAN,
            "MockitoBean", MockKind.MOCKITO_BEAN,
            "MockitoSpyBean", MockKind.MOCKITO_SPY_BEAN);

    /** Spring test-slice annotations recognized as "this test starts (a slice of) a context". */
    private static final Set<String> CONTEXT_SLICE_ANNOTATIONS = Set.of(
            "SpringBootTest", "WebMvcTest", "DataJpaTest", "JdbcTest", "WebFluxTest",
            "DataMongoTest", "RestClientTest", "JsonTest", "DataRedisTest", "DataLdapTest", "DataR2dbcTest");

    private static final String BEFORE_EACH_SCOPE = "@BeforeEach";

    /** How a field became a test double. */
    public enum MockKind {
        MOCK, SPY, MOCK_BEAN, SPY_BEAN, MOCKITO_BEAN, MOCKITO_SPY_BEAN, MOCK_CALL, SPY_CALL
    }

    /**
     * A type declared as a real (non-doubled) subject.
     *
     * @param fieldName   the declaring field
     * @param typeFqcn    the field's declared type, resolved via import/package like every
     *                    other index in this project
     * @param source      {@code "@InjectMocks"} or {@code "new"} (field-initializer or
     *                    {@code @BeforeEach}-assignment construction)
     * @param nestedScope simple name of the innermost {@code @Nested} class this was
     *                    declared in, dot-joined if nested more than one level deep, or
     *                    {@code null} if declared directly in the top-level test class
     */
    public record RealSubjectDeclaration(String fieldName, String typeFqcn, String source, String nestedScope) {
    }

    /**
     * A type declared as a test double.
     *
     * @param fieldName   the declaring field
     * @param typeFqcn    the field's declared type
     * @param kind        how it was doubled
     * @param nestedScope see {@link RealSubjectDeclaration#nestedScope()}
     */
    public record MockedDeclaration(String fieldName, String typeFqcn, MockKind kind, String nestedScope) {
    }

    /**
     * A type frozen via {@code mockStatic(X.class)}.
     *
     * @param typeFqcn    the frozen type
     * @param scope       the enclosing method's simple name, {@code "@BeforeEach"} if the
     *                    enclosing method carries that annotation, or the field name if
     *                    the call is a field initializer
     * @param nestedScope see {@link RealSubjectDeclaration#nestedScope()}
     */
    public record StaticMockDeclaration(String typeFqcn, String scope, String nestedScope) {
    }

    /**
     * @param classAnnotations source text of every class-level annotation on the top-level
     *                         test class (parameters preserved, e.g. a {@code @WebMvcTest}'s
     *                         {@code controllers=...})
     * @param extendWith       FQCNs (resolved via import) named in {@code @ExtendWith}, if any
     */
    public record SliceInfo(List<String> classAnnotations, List<String> extendWith) {
    }

    /** A declaration this index could not classify. Self-reported, never silently dropped. */
    public record UnresolvedDeclaration(String description, String reason) {
    }

    /** All wiring facts for one top-level test class, {@code @Nested} classes aggregated in. */
    public record TestClassWiring(
            String fqcn,
            List<RealSubjectDeclaration> realSubjects,
            List<MockedDeclaration> mocked,
            List<StaticMockDeclaration> staticMocks,
            SliceInfo slice,
            List<UnresolvedDeclaration> unresolved) {
    }

    private final Map<String, TestClassWiring> byFqcn;

    private TestWiringIndex(Map<String, TestClassWiring> byFqcn) {
        this.byFqcn = Map.copyOf(byFqcn);
    }

    /** Builds the index from an explicit list of test source files (shared parsing front-end). */
    public static TestWiringIndex build(List<Path> testFiles) {
        Map<String, TestClassWiring> byFqcn = new LinkedHashMap<>();
        for (ParsedSources.ParsedSource unit : ParsedSources.parse(testFiles).units()) {
            for (ClassOrInterfaceDeclaration topLevel : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!topLevel.isTopLevelType() || topLevel.isInterface()) {
                    continue;
                }
                topLevel.getFullyQualifiedName().ifPresent(fqcn ->
                        byFqcn.put(fqcn, analyze(fqcn, topLevel, unit.cu())));
            }
        }
        return new TestWiringIndex(byFqcn);
    }

    private static TestClassWiring analyze(String fqcn, ClassOrInterfaceDeclaration topLevel, CompilationUnit cu) {
        List<RealSubjectDeclaration> realSubjects = new ArrayList<>();
        List<MockedDeclaration> mocked = new ArrayList<>();
        List<StaticMockDeclaration> staticMocks = new ArrayList<>();
        List<UnresolvedDeclaration> unresolved = new ArrayList<>();

        List<ClassOrInterfaceDeclaration> scopes = new ArrayList<>();
        scopes.add(topLevel);
        for (ClassOrInterfaceDeclaration nested : topLevel.findAll(ClassOrInterfaceDeclaration.class)) {
            if (nested != topLevel && hasAnnotation(nested, "Nested")) {
                scopes.add(nested);
            }
        }

        for (ClassOrInterfaceDeclaration scope : scopes) {
            String nestedScope = scope == topLevel ? null : nestedScopeChain(scope, topLevel);
            analyzeFields(scope, cu, fqcn, nestedScope, realSubjects, mocked, staticMocks, unresolved);
            analyzeMethods(scope, cu, fqcn, nestedScope, realSubjects, mocked, staticMocks, unresolved);
        }

        SliceInfo slice = sliceInfoOf(topLevel, cu);
        return new TestClassWiring(fqcn, List.copyOf(realSubjects), List.copyOf(mocked),
                List.copyOf(staticMocks), slice, List.copyOf(unresolved));
    }

    // --- fields: annotations + initializer construction ---

    private static void analyzeFields(
            ClassOrInterfaceDeclaration scope, CompilationUnit cu, String fqcn, String nestedScope,
            List<RealSubjectDeclaration> realSubjects, List<MockedDeclaration> mocked,
            List<StaticMockDeclaration> staticMocks, List<UnresolvedDeclaration> unresolved) {
        for (FieldDeclaration field : scope.getFields()) {
            for (AnnotationExpr annotation : field.getAnnotations()) {
                classifyAnnotatedField(annotation, field, cu, fqcn, nestedScope, realSubjects, mocked, unresolved);
            }
            for (VariableDeclarator variable : field.getVariables()) {
                variable.getInitializer().ifPresent(init -> classifyFieldInitializer(
                        init, variable.getNameAsString(), cu, fqcn, nestedScope, realSubjects, mocked, staticMocks,
                        unresolved));
            }
        }
    }

    private static void classifyAnnotatedField(
            AnnotationExpr annotation, FieldDeclaration field, CompilationUnit cu, String fqcn, String nestedScope,
            List<RealSubjectDeclaration> realSubjects, List<MockedDeclaration> mocked,
            List<UnresolvedDeclaration> unresolved) {
        String simple = annotation.getName().getIdentifier();
        String expectedFqcn = KNOWN_MOCK_ANNOTATION_FQCNS.get(simple);
        if (expectedFqcn == null) {
            return; // not a mock-like annotation at all; nothing to say about it
        }
        if (!importMatches(cu, expectedFqcn)) {
            unresolved.add(new UnresolvedDeclaration(
                    "@" + simple + " field in " + scopeLabel(fqcn, nestedScope),
                    "import does not resolve to " + expectedFqcn
                            + "; a same-named annotation from elsewhere is not classified"));
            return;
        }
        for (VariableDeclarator variable : field.getVariables()) {
            String typeFqcn = typeFqcnOf(variable.getType(), cu);
            String fieldName = variable.getNameAsString();
            if ("InjectMocks".equals(simple)) {
                realSubjects.add(new RealSubjectDeclaration(fieldName, typeFqcn, "@InjectMocks", nestedScope));
            } else {
                mocked.add(new MockedDeclaration(fieldName, typeFqcn, MOCK_KIND_BY_ANNOTATION.get(simple), nestedScope));
            }
        }
    }

    private static void classifyFieldInitializer(
            Expression init, String fieldName, CompilationUnit cu, String fqcn, String nestedScope,
            List<RealSubjectDeclaration> realSubjects, List<MockedDeclaration> mocked,
            List<StaticMockDeclaration> staticMocks, List<UnresolvedDeclaration> unresolved) {
        if (init instanceof MethodCallExpr call && "mockStatic".equals(call.getNameAsString())) {
            classifyMockStaticCall(call, cu, fqcn, nestedScope, fieldName, staticMocks, unresolved);
            return;
        }
        classifyConstructionExpr(init, cu).ifPresent(c -> recordClassification(c, fieldName, nestedScope, realSubjects, mocked));
    }

    // --- methods: mockStatic (any method) + new/mock/spy field assignment (@BeforeEach only) ---

    private static void analyzeMethods(
            ClassOrInterfaceDeclaration scope, CompilationUnit cu, String fqcn, String nestedScope,
            List<RealSubjectDeclaration> realSubjects, List<MockedDeclaration> mocked,
            List<StaticMockDeclaration> staticMocks, List<UnresolvedDeclaration> unresolved) {
        Set<String> fieldNames = fieldNamesOf(scope);
        for (MethodDeclaration method : scope.getMethods()) {
            boolean isBeforeEach = hasAnnotation(method, "BeforeEach");
            String scopeLabel = isBeforeEach ? BEFORE_EACH_SCOPE : method.getNameAsString();

            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if ("mockStatic".equals(call.getNameAsString())) {
                    classifyMockStaticCall(call, cu, fqcn, nestedScope, scopeLabel, staticMocks, unresolved);
                }
            }

            if (isBeforeEach) {
                for (AssignExpr assign : method.findAll(AssignExpr.class)) {
                    String target = simpleTargetName(assign.getTarget());
                    if (target == null || !fieldNames.contains(target)) {
                        continue;
                    }
                    classifyConstructionExpr(assign.getValue(), cu)
                            .ifPresent(c -> recordClassification(c, target, nestedScope, realSubjects, mocked));
                }
            }
        }
    }

    private static void classifyMockStaticCall(
            MethodCallExpr call, CompilationUnit cu, String fqcn, String nestedScope, String scopeLabel,
            List<StaticMockDeclaration> staticMocks, List<UnresolvedDeclaration> unresolved) {
        Optional<ClassOrInterfaceType> literalType = classLiteralArgument(call);
        if (literalType.isEmpty()) {
            unresolved.add(new UnresolvedDeclaration(
                    "mockStatic(...) call (scope " + scopeLabel + ") in " + scopeLabel(fqcn, nestedScope),
                    "argument is not a class literal; cannot determine the frozen static type"));
            return;
        }
        staticMocks.add(new StaticMockDeclaration(resolveTypeFqcn(literalType.get().getNameAsString(), cu),
                scopeLabel, nestedScope));
    }

    private static Optional<ClassOrInterfaceType> classLiteralArgument(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression arg = call.getArguments().get(0);
        if (arg instanceof ClassExpr classExpr && classExpr.getType() instanceof ClassOrInterfaceType cit) {
            return Optional.of(cit);
        }
        return Optional.empty();
    }

    // --- shared construction-expression classification: new X(...) / mock(X.class) / spy(...) ---

    private enum ClassificationKind { NEW, MOCK_CALL, SPY_CALL }

    private record Classification(ClassificationKind kind, String typeFqcn) {
    }

    private static Optional<Classification> classifyConstructionExpr(Expression expr, CompilationUnit cu) {
        if (expr instanceof ObjectCreationExpr oce) {
            return Optional.of(new Classification(ClassificationKind.NEW, typeFqcnOf(oce.getType(), cu)));
        }
        if (expr instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            if ("mock".equals(name) || "spy".equals(name)) {
                return resolveConstructionArgument(call, cu)
                        .map(type -> new Classification(
                                "mock".equals(name) ? ClassificationKind.MOCK_CALL : ClassificationKind.SPY_CALL,
                                type));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> resolveConstructionArgument(MethodCallExpr call, CompilationUnit cu) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression arg = call.getArguments().get(0);
        if (arg instanceof ClassExpr classExpr && classExpr.getType() instanceof ClassOrInterfaceType cit) {
            return Optional.of(resolveTypeFqcn(cit.getNameAsString(), cu));
        }
        if (arg instanceof ObjectCreationExpr oce) {
            return Optional.of(typeFqcnOf(oce.getType(), cu));
        }
        return Optional.empty();
    }

    private static void recordClassification(
            Classification c, String fieldName, String nestedScope,
            List<RealSubjectDeclaration> realSubjects, List<MockedDeclaration> mocked) {
        switch (c.kind()) {
            case NEW -> realSubjects.add(new RealSubjectDeclaration(fieldName, c.typeFqcn(), "new", nestedScope));
            case MOCK_CALL -> mocked.add(new MockedDeclaration(fieldName, c.typeFqcn(), MockKind.MOCK_CALL, nestedScope));
            case SPY_CALL -> mocked.add(new MockedDeclaration(fieldName, c.typeFqcn(), MockKind.SPY_CALL, nestedScope));
        }
    }

    // --- slice info ---

    private static SliceInfo sliceInfoOf(ClassOrInterfaceDeclaration topLevel, CompilationUnit cu) {
        List<String> classAnnotations = topLevel.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .toList();
        List<String> extendWith = topLevel.getAnnotations().stream()
                .filter(a -> "ExtendWith".equals(a.getName().getIdentifier()))
                .flatMap(a -> classLiteralValues(a).stream())
                .map(cit -> resolveTypeFqcn(cit.getNameAsString(), cu))
                .toList();
        return new SliceInfo(classAnnotations, extendWith);
    }

    private static List<ClassOrInterfaceType> classLiteralValues(AnnotationExpr annotation) {
        List<Expression> values = new ArrayList<>();
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            addExpandingArray(values, single.getMemberValue());
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            normal.getPairs().stream()
                    .filter(pair -> "value".equals(pair.getNameAsString()))
                    .forEach(pair -> addExpandingArray(values, pair.getValue()));
        }
        List<ClassOrInterfaceType> types = new ArrayList<>();
        for (Expression value : values) {
            if (value instanceof ClassExpr classExpr && classExpr.getType() instanceof ClassOrInterfaceType cit) {
                types.add(cit);
            }
        }
        return types;
    }

    private static void addExpandingArray(List<Expression> target, Expression value) {
        if (value instanceof ArrayInitializerExpr array) {
            target.addAll(array.getValues());
        } else {
            target.add(value);
        }
    }

    // --- helpers shared across the above ---

    private static Set<String> fieldNamesOf(ClassOrInterfaceDeclaration scope) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration field : scope.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                names.add(variable.getNameAsString());
            }
        }
        return names;
    }

    private static String simpleTargetName(Expression target) {
        if (target instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (target instanceof FieldAccessExpr fieldAccess && fieldAccess.getScope().isThisExpr()) {
            return fieldAccess.getNameAsString();
        }
        return null;
    }

    private static String nestedScopeChain(ClassOrInterfaceDeclaration scope, ClassOrInterfaceDeclaration topLevel) {
        List<String> chain = new ArrayList<>();
        Node current = scope;
        while (current instanceof ClassOrInterfaceDeclaration c && c != topLevel) {
            chain.add(0, c.getNameAsString());
            current = c.getParentNode().orElse(null);
        }
        return String.join(".", chain);
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals(simpleName));
    }

    private static String scopeLabel(String fqcn, String nestedScope) {
        return nestedScope == null ? fqcn : fqcn + " (" + nestedScope + ")";
    }

    private static String typeFqcnOf(Type type, CompilationUnit cu) {
        if (type instanceof ClassOrInterfaceType cit) {
            return resolveTypeFqcn(cit.getNameAsString(), cu);
        }
        return type.asString();
    }

    /** Import-based resolution, same spirit as every other index: explicit import wins, same package otherwise. */
    private static String resolveTypeFqcn(String simpleName, CompilationUnit cu) {
        return cu.getImports().stream()
                .filter(imp -> !imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + simpleName))
                .map(ImportDeclaration::getNameAsString)
                .findFirst()
                .orElseGet(() -> cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString() + "." + simpleName)
                        .orElse(simpleName));
    }

    /** Whether an import in {@code cu} actually resolves to {@code expectedFqcn} (exact or matching wildcard). */
    private static boolean importMatches(CompilationUnit cu, String expectedFqcn) {
        int lastDot = expectedFqcn.lastIndexOf('.');
        String expectedPackage = lastDot < 0 ? "" : expectedFqcn.substring(0, lastDot);
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) {
                continue;
            }
            if (imp.isAsterisk() ? imp.getNameAsString().equals(expectedPackage)
                    : imp.getNameAsString().equals(expectedFqcn)) {
                return true;
            }
        }
        return false;
    }

    // --- public queries ---

    /** Every analyzed test class, sorted by FQCN. */
    public List<TestClassWiring> all() {
        return byFqcn.values().stream().sorted(Comparator.comparing(TestClassWiring::fqcn)).toList();
    }

    /** Wiring for the test class with this exact FQCN. */
    public Optional<TestClassWiring> wiringOf(String fqcn) {
        return Optional.ofNullable(byFqcn.get(fqcn));
    }

    /**
     * Test classes matching {@code className}: an exact FQCN match wins alone; otherwise
     * every test class whose simple name equals it (possibly several — ambiguous).
     */
    public List<TestClassWiring> findByName(String className) {
        TestClassWiring exact = byFqcn.get(className);
        if (exact != null) {
            return List.of(exact);
        }
        return byFqcn.values().stream()
                .filter(w -> simpleNameOf(w.fqcn()).equals(className))
                .sorted(Comparator.comparing(TestClassWiring::fqcn))
                .toList();
    }

    /** FQCNs of test classes that declare {@code fqcn} as a real (non-doubled) subject. */
    public List<String> realSubjectClasses(String fqcn) {
        return all().stream()
                .filter(w -> w.realSubjects().stream().anyMatch(r -> fqcn.equals(r.typeFqcn())))
                .map(TestClassWiring::fqcn)
                .sorted()
                .toList();
    }

    /** FQCNs of test classes that declare {@code fqcn} as a test double (any {@link MockKind}). */
    public List<String> mockedClasses(String fqcn) {
        return all().stream()
                .filter(w -> w.mocked().stream().anyMatch(m -> fqcn.equals(m.typeFqcn())))
                .map(TestClassWiring::fqcn)
                .sorted()
                .toList();
    }

    /** FQCNs of test classes that freeze {@code fqcn} via {@code mockStatic}. */
    public List<String> staticMockedClasses(String fqcn) {
        return all().stream()
                .filter(w -> w.staticMocks().stream().anyMatch(s -> fqcn.equals(s.typeFqcn())))
                .map(TestClassWiring::fqcn)
                .sorted()
                .toList();
    }

    /**
     * FQCNs of context-slice test classes ({@code @SpringBootTest}/{@code @WebMvcTest}/etc.)
     * that never mention {@code fqcn} in any of their declarations. Not proof {@code fqcn}
     * plays no role in these tests — resolving what a Spring context actually wires is out
     * of scope for a static analysis — only that this index found no textual reference,
     * self-reported as such rather than treated as "known safe".
     */
    public List<String> unclassifiedContextTests(String fqcn) {
        return all().stream()
                .filter(TestWiringIndex::isContextTest)
                .filter(w -> !mentions(w, fqcn))
                .map(TestClassWiring::fqcn)
                .sorted()
                .toList();
    }

    private static boolean isContextTest(TestClassWiring wiring) {
        return wiring.slice().classAnnotations().stream().anyMatch(TestWiringIndex::isContextSliceAnnotationText);
    }

    private static boolean isContextSliceAnnotationText(String annotationText) {
        String s = annotationText.startsWith("@") ? annotationText.substring(1) : annotationText;
        int end = 0;
        while (end < s.length() && Character.isJavaIdentifierPart(s.charAt(end))) {
            end++;
        }
        return CONTEXT_SLICE_ANNOTATIONS.contains(s.substring(0, end));
    }

    private static boolean mentions(TestClassWiring wiring, String fqcn) {
        return wiring.realSubjects().stream().anyMatch(r -> fqcn.equals(r.typeFqcn()))
                || wiring.mocked().stream().anyMatch(m -> fqcn.equals(m.typeFqcn()))
                || wiring.staticMocks().stream().anyMatch(s -> fqcn.equals(s.typeFqcn()));
    }

    private static String simpleNameOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
