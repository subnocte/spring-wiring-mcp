package io.github.subnocte.springwiring.bean;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import io.github.subnocte.springwiring.scanner.ParsedSources;
import io.github.subnocte.springwiring.scanner.SourceScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory index of Spring beans and their field-level dependency edges, built once
 * from the scanned sources. Resolution follows the same contract as the endpoint index:
 * every site that cannot be resolved statically is reported with a reason, never guessed.
 *
 * <p>Dependency sites are the bean's non-static, non-initialized instance fields — one
 * rule that covers field {@code @Autowired}, Lombok-generated constructors, and
 * hand-written constructor injection without expanding Lombok or analyzing constructors.
 * A constructor parameter never stored in a field is invisible by design: that produces
 * a missing edge, never a wrong one.
 */
public final class BeanIndex {

    /** Class-level stereotype annotations, most specific first (a class may carry several). */
    private static final List<String> STEREOTYPES = List.of(
            "RestController", "Controller", "Service", "Repository", "Configuration", "Component");

    /** Interfaces implemented by a framework at runtime when extended: Spring Data repositories. */
    private static final Set<String> REPOSITORY_BASES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "MongoRepository",
            "ElasticsearchRepository", "R2dbcRepository", "CoroutineCrudRepository");

    /** Container types whose element resolution is out of scope in v1. */
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Set", "Collection", "Map");

    /** Field types that are never bean dependencies. */
    private static final Set<String> NON_BEAN_SIMPLE_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Object", "BigDecimal", "BigInteger");

    private final Map<String, BeanDefinition> beansByFqcn;
    private final Map<String, BeanDependencies> dependenciesByFqcn;

    private BeanIndex(Map<String, BeanDefinition> beansByFqcn, Map<String, BeanDependencies> dependenciesByFqcn) {
        this.beansByFqcn = Map.copyOf(beansByFqcn);
        this.dependenciesByFqcn = Map.copyOf(dependenciesByFqcn);
    }

    /** Scans {@code root} recursively and builds the bean index. */
    public static BeanIndex forRoot(Path root) {
        return build(SourceScanner.scan(root));
    }

    private record ParsedUnit(CompilationUnit cu, Path path) {
    }

    private record TypeEntry(ClassOrInterfaceDeclaration decl, CompilationUnit cu, Path path) {
    }

    /** Builds an index from an explicit list of source files. Files that fail to parse are reported
     * through the endpoint index's parse-failure list (both indexes share the parsing front-end). */
    public static BeanIndex build(List<Path> sourceFiles) {
        List<ParsedUnit> units = ParsedSources.parse(sourceFiles).units().stream()
                .map(u -> new ParsedUnit(u.cu(), u.path()))
                .toList();

        Map<String, TypeEntry> typeTable = new HashMap<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                decl.getFullyQualifiedName().ifPresent(
                        fqcn -> typeTable.put(fqcn, new TypeEntry(decl, unit.cu(), unit.path())));
            }
        }

        // --- bean universe ---
        Map<String, BeanDefinition> beans = new LinkedHashMap<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = decl.getFullyQualifiedName().orElse(null);
                if (fqcn == null) {
                    continue;
                }
                int line = decl.getBegin().map(p -> p.line).orElse(-1);
                if (!decl.isInterface()) {
                    stereotypeOf(decl).ifPresent(stereotype -> beans.put(fqcn,
                            new BeanDefinition(fqcn, stereotype, unit.path().toString(), line, false)));
                } else if (hasAnnotation(decl, "Mapper")) {
                    beans.put(fqcn, new BeanDefinition(fqcn, "Mapper", unit.path().toString(), line, true));
                } else {
                    repositoryBaseOf(decl).ifPresent(base -> beans.put(fqcn,
                            new BeanDefinition(fqcn, base, unit.path().toString(), line, true)));
                }
            }
        }
        // @Bean factory methods contribute their return type to the universe (stereotyped
        // classes win on collision). Their own wiring (@Bean method parameters) is not
        // analyzed in v1: such beans get an empty edge list below.
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface() || !hasAnnotation(decl, "Configuration")) {
                    continue;
                }
                for (MethodDeclaration method : decl.getMethods()) {
                    if (!hasAnnotation(method, "Bean")) {
                        continue;
                    }
                    Optional<TypeEntry> returned = entryOf(method.getType(), unit.cu(), typeTable);
                    returned.flatMap(e -> e.decl().getFullyQualifiedName()).ifPresent(fqcn ->
                            beans.putIfAbsent(fqcn, new BeanDefinition(
                                    fqcn, "Bean", unit.path().toString(),
                                    method.getBegin().map(p -> p.line).orElse(-1), false)));
                }
            }
        }

        // --- interface -> implementing beans (only beans are injectable candidates) ---
        Map<String, List<String>> implementations = new HashMap<>();
        for (BeanDefinition bean : beans.values()) {
            TypeEntry entry = typeTable.get(bean.fqcn());
            if (entry == null || entry.decl().isInterface()) {
                continue;
            }
            for (ClassOrInterfaceType iface : entry.decl().getImplementedTypes()) {
                entryOf(iface, entry.cu(), typeTable)
                        .flatMap(e -> e.decl().getFullyQualifiedName())
                        .ifPresent(ifaceFqcn -> implementations
                                .computeIfAbsent(ifaceFqcn, k -> new ArrayList<>())
                                .add(bean.fqcn()));
            }
        }

        // --- edges ---
        Map<String, BeanDependencies> dependencies = new LinkedHashMap<>();
        for (BeanDefinition bean : beans.values()) {
            TypeEntry entry = typeTable.get(bean.fqcn());
            if (bean.terminal() || entry == null || "Bean".equals(bean.stereotype())) {
                dependencies.put(bean.fqcn(), new BeanDependencies(bean, List.of()));
                continue;
            }
            List<BeanEdge> edges = new ArrayList<>();
            for (FieldDeclaration field : entry.decl().getFields()) {
                if (field.isStatic()) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getInitializer().isPresent()) {
                        continue;
                    }
                    edgeFor(field, variable, entry, typeTable, beans, implementations)
                            .ifPresent(edges::add);
                }
            }
            dependencies.put(bean.fqcn(), new BeanDependencies(bean, List.copyOf(edges)));
        }

        return new BeanIndex(beans, dependencies);
    }

    /**
     * Builds the edge for one field, or empty when the field's type can never be a bean
     * dependency (primitives, String and friends, {@code java.*}/{@code javax.*} types).
     */
    private static Optional<BeanEdge> edgeFor(
            FieldDeclaration field, VariableDeclarator variable, TypeEntry owner,
            Map<String, TypeEntry> typeTable, Map<String, BeanDefinition> beans,
            Map<String, List<String>> implementations) {
        Type type = variable.getType();
        if (type.isPrimitiveType() || !(type instanceof ClassOrInterfaceType cit)) {
            return Optional.empty();
        }
        String rawName = cit.getNameAsString();
        String declared = cit.asString();
        int line = field.getBegin().map(p -> p.line).orElse(-1);

        if (NON_BEAN_SIMPLE_TYPES.contains(rawName) || isJdkQualified(declared)) {
            return Optional.empty();
        }
        if (COLLECTION_TYPES.contains(rawName)) {
            return Optional.of(new BeanEdge(variable.getNameAsString(), declared, null,
                    BeanEdge.STATUS_UNRESOLVED, null, BeanEdge.REASON_COLLECTION_INJECTION,
                    null, List.of(), line));
        }

        Optional<TypeEntry> resolved = entryOf(cit, owner.cu(), typeTable);
        if (resolved.isEmpty()) {
            return Optional.of(new BeanEdge(variable.getNameAsString(), declared,
                    importedFqcn(rawName, owner.cu()).orElse(null),
                    BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line));
        }
        String fqcn = resolved.get().decl().getFullyQualifiedName().orElse(rawName);
        String fieldName = variable.getNameAsString();

        BeanDefinition direct = beans.get(fqcn);
        if (direct != null && !resolved.get().decl().isInterface()) {
            return Optional.of(resolvedEdge(fieldName, declared, fqcn, BeanEdge.KIND_CONCRETE, direct, line));
        }
        if (direct != null && direct.terminal()) {
            return Optional.of(resolvedEdge(fieldName, declared, fqcn, BeanEdge.KIND_TERMINAL, direct, line));
        }
        if (resolved.get().decl().isInterface()) {
            return Optional.of(interfaceEdge(field, fieldName, declared, fqcn,
                    typeTable, beans, implementations, line));
        }
        // A scanned concrete class that is not a bean: injectable only via configuration
        // the index does not see, so report it as outside the bean universe.
        return Optional.of(new BeanEdge(fieldName, declared, fqcn,
                BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line));
    }

    private static BeanEdge interfaceEdge(
            FieldDeclaration field, String fieldName, String declared, String ifaceFqcn,
            Map<String, TypeEntry> typeTable, Map<String, BeanDefinition> beans,
            Map<String, List<String>> implementations, int line) {
        List<BeanDefinition> candidates = implementations.getOrDefault(ifaceFqcn, List.of()).stream()
                .map(beans::get)
                .toList();

        if (candidates.isEmpty()) {
            return new BeanEdge(fieldName, declared, ifaceFqcn, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_NO_IMPLEMENTATION_FOUND, null, List.of(), line);
        }

        // @Qualifier on the field beats everything, per Spring semantics.
        Optional<String> qualifier = annotationStringValue(field, "Qualifier");
        if (qualifier.isPresent()) {
            Optional<BeanDefinition> named = candidates.stream()
                    .filter(c -> beanName(c, typeTable).equals(qualifier.get()))
                    .findFirst();
            if (named.isPresent()) {
                return resolvedEdge(fieldName, declared, ifaceFqcn, BeanEdge.KIND_QUALIFIER,
                        named.get(), line);
            }
            return new BeanEdge(fieldName, declared, ifaceFqcn, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_MULTIPLE_CANDIDATES, null, candidates, line);
        }

        // Any conditional candidate makes the winner environment-dependent: report, don't guess.
        boolean conditional = candidates.stream()
                .anyMatch(c -> isConditional(typeTable.get(c.fqcn())));
        if (conditional) {
            return new BeanEdge(fieldName, declared, ifaceFqcn, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_CONDITIONAL_CANDIDATES, null, candidates, line);
        }

        if (candidates.size() == 1) {
            return resolvedEdge(fieldName, declared, ifaceFqcn, BeanEdge.KIND_SINGLE_IMPLEMENTATION,
                    candidates.get(0), line);
        }
        List<BeanDefinition> primaries = candidates.stream()
                .filter(c -> {
                    TypeEntry e = typeTable.get(c.fqcn());
                    return e != null && hasAnnotation(e.decl(), "Primary");
                })
                .toList();
        if (primaries.size() == 1) {
            return resolvedEdge(fieldName, declared, ifaceFqcn, BeanEdge.KIND_PRIMARY,
                    primaries.get(0), line);
        }
        return new BeanEdge(fieldName, declared, ifaceFqcn, BeanEdge.STATUS_UNRESOLVED,
                null, BeanEdge.REASON_MULTIPLE_CANDIDATES, null, candidates, line);
    }

    private static BeanEdge resolvedEdge(
            String fieldName, String declared, String declaredFqcn, String kind,
            BeanDefinition target, int line) {
        return new BeanEdge(fieldName, declared, declaredFqcn, BeanEdge.STATUS_RESOLVED,
                kind, null, target, List.of(), line);
    }

    /** The bean's injection name: explicit stereotype value, or the decapitalized simple name. */
    private static String beanName(BeanDefinition bean, Map<String, TypeEntry> typeTable) {
        TypeEntry entry = typeTable.get(bean.fqcn());
        if (entry != null) {
            for (String stereotype : STEREOTYPES) {
                Optional<String> explicit = annotationStringValue(entry.decl(), stereotype);
                if (explicit.isPresent()) {
                    return explicit.get();
                }
            }
        }
        String simple = bean.simpleName();
        return simple.isEmpty() ? simple : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static boolean isConditional(TypeEntry entry) {
        return entry != null && entry.decl().getAnnotations().stream()
                .map(BeanIndex::simpleNameOf)
                .anyMatch(n -> n.equals("Profile") || n.startsWith("ConditionalOn"));
    }

    private static Optional<String> stereotypeOf(ClassOrInterfaceDeclaration decl) {
        for (String stereotype : STEREOTYPES) {
            if (hasAnnotation(decl, stereotype)) {
                return Optional.of(stereotype);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> repositoryBaseOf(ClassOrInterfaceDeclaration decl) {
        return decl.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .filter(REPOSITORY_BASES::contains)
                .findFirst();
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream().anyMatch(a -> simpleNameOf(a).equals(simpleName));
    }

    private static String simpleNameOf(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }

    private static Optional<String> annotationStringValue(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream()
                .filter(a -> simpleNameOf(a).equals(simpleName))
                .findFirst()
                .flatMap(a -> a instanceof SingleMemberAnnotationExpr single
                        && single.getMemberValue() instanceof StringLiteralExpr literal
                        ? Optional.of(literal.asString()) : Optional.empty());
    }

    private static boolean isJdkQualified(String declared) {
        return declared.startsWith("java.") || declared.startsWith("javax.");
    }

    /** Same resolution order as the endpoint index: FQCN, explicit import, package, wildcard import. */
    private static Optional<TypeEntry> entryOf(Type type, CompilationUnit cu, Map<String, TypeEntry> typeTable) {
        if (!(type instanceof ClassOrInterfaceType cit)) {
            return Optional.empty();
        }
        String name = cit.getNameWithScope();
        if (name.contains(".")) {
            return Optional.ofNullable(typeTable.get(name));
        }
        Optional<String> imported = importedFqcn(name, cu);
        if (imported.isPresent()) {
            return Optional.ofNullable(typeTable.get(imported.get()));
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

    private static Optional<String> importedFqcn(String simpleName, CompilationUnit cu) {
        return cu.getImports().stream()
                .filter(imp -> !imp.isStatic() && !imp.isAsterisk()
                        && imp.getNameAsString().endsWith("." + simpleName))
                .map(ImportDeclaration::getNameAsString)
                .findFirst();
    }

    /** Every bean in the universe, sorted by FQCN. */
    public List<BeanDefinition> allBeans() {
        return beansByFqcn.values().stream()
                .sorted(Comparator.comparing(BeanDefinition::fqcn))
                .toList();
    }

    /**
     * Beans matching {@code className}: an exact FQCN match wins alone; otherwise all
     * beans whose simple name equals it (possibly several).
     */
    public List<BeanDefinition> findByName(String className) {
        BeanDefinition exact = beansByFqcn.get(className);
        if (exact != null) {
            return List.of(exact);
        }
        return beansByFqcn.values().stream()
                .filter(b -> b.simpleName().equals(className))
                .sorted(Comparator.comparing(BeanDefinition::fqcn))
                .toList();
    }

    /** Dependency edges of the bean with this exact FQCN; empty if it is not a bean. */
    public Optional<BeanDependencies> dependenciesOf(String fqcn) {
        return Optional.ofNullable(dependenciesByFqcn.get(fqcn));
    }

    /** Count of unresolved dependency sites across all beans, grouped by reason. */
    public Map<String, Long> unresolvedInjectionCountByReason() {
        return dependenciesByFqcn.values().stream()
                .flatMap(d -> d.edges().stream())
                .filter(e -> BeanEdge.STATUS_UNRESOLVED.equals(e.status()))
                .collect(Collectors.groupingBy(BeanEdge::reason, Collectors.counting()));
    }
}
