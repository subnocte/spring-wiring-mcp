package io.github.subnocte.springwiring.bean;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
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
        Set<String> customStereotypes = customStereotypesOf(units);
        Map<String, BeanDefinition> beans = new LinkedHashMap<>();
        for (ParsedUnit unit : units) {
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = decl.getFullyQualifiedName().orElse(null);
                if (fqcn == null) {
                    continue;
                }
                int line = decl.getBegin().map(p -> p.line).orElse(-1);
                if (!decl.isInterface()) {
                    stereotypeOf(decl, customStereotypes).ifPresent(stereotype -> beans.put(fqcn,
                            new BeanDefinition(fqcn, stereotype, unit.path().toString(), line, false)));
                } else if (hasAnnotation(decl, "Mapper")) {
                    beans.put(fqcn, new BeanDefinition(fqcn, "Mapper", unit.path().toString(), line, true));
                } else {
                    repositoryBaseOf(decl, unit.cu(), typeTable, new java.util.HashSet<>())
                            .ifPresent(base -> beans.put(fqcn,
                                    new BeanDefinition(fqcn, base, unit.path().toString(), line, true)));
                }
            }
        }
        // @Bean factory methods contribute their return type to the universe (stereotyped
        // classes win on collision); the factory method's parameters become the produced
        // bean's dependency edges.
        Map<String, FactoryMethod> factoryMethods = new HashMap<>();
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
                    returned.flatMap(e -> e.decl().getFullyQualifiedName()).ifPresent(fqcn -> {
                        beans.putIfAbsent(fqcn, new BeanDefinition(
                                fqcn, "Bean", unit.path().toString(),
                                method.getBegin().map(p -> p.line).orElse(-1), false));
                        factoryMethods.putIfAbsent(fqcn, new FactoryMethod(method, unit.cu()));
                    });
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
            if (bean.terminal() || (entry == null && !factoryMethods.containsKey(bean.fqcn()))) {
                dependencies.put(bean.fqcn(), new BeanDependencies(bean, List.of()));
                continue;
            }
            List<BeanEdge> edges = new ArrayList<>();
            if ("Bean".equals(bean.stereotype())) {
                FactoryMethod factory = factoryMethods.get(bean.fqcn());
                if (factory != null) {
                    for (Parameter param : factory.method().getParameters()) {
                        int line = param.getBegin().map(p -> p.line)
                                .orElse(factory.method().getBegin().map(p -> p.line).orElse(-1));
                        siteEdge(param.getNameAsString(), param.getType(), param, factory.cu(), line,
                                typeTable, beans, implementations).ifPresent(edges::add);
                    }
                }
            } else {
                for (FieldDeclaration field : entry.decl().getFields()) {
                    if (field.isStatic()) {
                        continue;
                    }
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (variable.getInitializer().isPresent()) {
                            continue;
                        }
                        int line = field.getBegin().map(p -> p.line).orElse(-1);
                        siteEdge(variable.getNameAsString(), variable.getType(), field, entry.cu(), line,
                                typeTable, beans, implementations).ifPresent(edges::add);
                    }
                }
            }
            dependencies.put(bean.fqcn(), new BeanDependencies(bean, List.copyOf(edges)));
        }

        return new BeanIndex(beans, dependencies);
    }

    /** A {@code @Bean} factory method and the compilation unit its types resolve in. */
    private record FactoryMethod(MethodDeclaration method, CompilationUnit cu) {
    }

    /**
     * Annotations declared in the scanned sources that act as stereotypes because they
     * are themselves annotated with one, directly or through another custom stereotype.
     */
    private static Set<String> customStereotypesOf(List<ParsedUnit> units) {
        Map<String, List<String>> annotationsOn = new HashMap<>();
        for (ParsedUnit unit : units) {
            for (AnnotationDeclaration decl : unit.cu().findAll(AnnotationDeclaration.class)) {
                annotationsOn.put(decl.getNameAsString(),
                        decl.getAnnotations().stream().map(BeanIndex::simpleNameOf).toList());
            }
        }
        Set<String> custom = new java.util.HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, List<String>> entry : annotationsOn.entrySet()) {
                if (custom.contains(entry.getKey())) {
                    continue;
                }
                boolean stereotype = entry.getValue().stream()
                        .anyMatch(a -> STEREOTYPES.contains(a) || custom.contains(a));
                if (stereotype) {
                    custom.add(entry.getKey());
                    changed = true;
                }
            }
        }
        return custom;
    }

    /**
     * Builds the edge for one dependency site (an instance field or a {@code @Bean} method
     * parameter), or empty when the site's type can never be a bean dependency (primitives,
     * String and friends, {@code java.*}/{@code javax.*} types).
     */
    private static Optional<BeanEdge> siteEdge(
            String siteName, Type type, NodeWithAnnotations<?> site, CompilationUnit cu, int line,
            Map<String, TypeEntry> typeTable, Map<String, BeanDefinition> beans,
            Map<String, List<String>> implementations) {
        if (type.isPrimitiveType() || !(type instanceof ClassOrInterfaceType cit)) {
            return Optional.empty();
        }
        String rawName = cit.getNameAsString();
        String declared = cit.asString();

        if (NON_BEAN_SIMPLE_TYPES.contains(rawName) || isJdkQualified(declared)) {
            return Optional.empty();
        }
        if (COLLECTION_TYPES.contains(rawName)) {
            return Optional.of(collectionEdge(siteName, cit, cu, line, typeTable, beans, implementations));
        }

        Optional<TypeEntry> resolved = entryOf(cit, cu, typeTable);
        if (resolved.isEmpty()) {
            return Optional.of(new BeanEdge(siteName, declared,
                    importedFqcn(rawName, cu).orElse(null),
                    BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line));
        }
        String fqcn = resolved.get().decl().getFullyQualifiedName().orElse(rawName);

        BeanDefinition direct = beans.get(fqcn);
        if (direct != null && !resolved.get().decl().isInterface()) {
            return Optional.of(resolvedEdge(siteName, declared, fqcn, BeanEdge.KIND_CONCRETE, direct, line));
        }
        if (direct != null && direct.terminal()) {
            return Optional.of(resolvedEdge(siteName, declared, fqcn, BeanEdge.KIND_TERMINAL, direct, line));
        }
        if (resolved.get().decl().isInterface()) {
            return Optional.of(interfaceEdge(site, siteName, declared, fqcn,
                    typeTable, beans, implementations, line));
        }
        // A scanned concrete class that is not a bean: injectable only via configuration
        // the index does not see, so report it as outside the bean universe.
        return Optional.of(new BeanEdge(siteName, declared, fqcn,
                BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line));
    }

    /**
     * Resolves a {@code List}/{@code Set}/{@code Collection}/{@code Map<String, _>} site to
     * ALL element implementations (Spring binds every matching bean). Raw types and maps
     * with non-String keys stay reported as collection-injection.
     */
    private static BeanEdge collectionEdge(
            String siteName, ClassOrInterfaceType cit, CompilationUnit cu, int line,
            Map<String, TypeEntry> typeTable, Map<String, BeanDefinition> beans,
            Map<String, List<String>> implementations) {
        String declared = cit.asString();
        Type elementType = null;
        if (cit.getTypeArguments().isPresent()) {
            List<Type> args = cit.getTypeArguments().get();
            if (cit.getNameAsString().equals("Map")) {
                if (args.size() == 2 && args.get(0) instanceof ClassOrInterfaceType key
                        && key.getNameAsString().equals("String")) {
                    elementType = args.get(1);
                }
            } else if (args.size() == 1) {
                elementType = args.get(0);
            }
        }
        if (!(elementType instanceof ClassOrInterfaceType element)) {
            return new BeanEdge(siteName, declared, null, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_COLLECTION_INJECTION, null, List.of(), line);
        }

        Optional<TypeEntry> resolved = entryOf(element, cu, typeTable);
        if (resolved.isEmpty()) {
            return new BeanEdge(siteName, declared,
                    importedFqcn(element.getNameAsString(), cu).orElse(null),
                    BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line);
        }
        String elementFqcn = resolved.get().decl().getFullyQualifiedName()
                .orElse(element.getNameAsString());
        BeanDefinition direct = beans.get(elementFqcn);
        if (!resolved.get().decl().isInterface()) {
            return direct != null
                    ? collectionResolved(siteName, declared, elementFqcn, List.of(direct), line)
                    : new BeanEdge(siteName, declared, elementFqcn,
                            BeanEdge.STATUS_NOT_A_SCANNED_BEAN, null, null, null, List.of(), line);
        }
        if (direct != null && direct.terminal()) {
            return collectionResolved(siteName, declared, elementFqcn, List.of(direct), line);
        }
        List<BeanDefinition> elements = implementations.getOrDefault(elementFqcn, List.of()).stream()
                .map(beans::get)
                .toList();
        if (elements.isEmpty()) {
            return new BeanEdge(siteName, declared, elementFqcn, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_NO_IMPLEMENTATION_FOUND, null, List.of(), line);
        }
        return collectionResolved(siteName, declared, elementFqcn, elements, line);
    }

    private static BeanEdge collectionResolved(
            String siteName, String declared, String elementFqcn, List<BeanDefinition> elements, int line) {
        return new BeanEdge(siteName, declared, elementFqcn, BeanEdge.STATUS_RESOLVED,
                BeanEdge.KIND_COLLECTION, null, null, elements, line);
    }

    private static BeanEdge interfaceEdge(
            NodeWithAnnotations<?> site, String fieldName, String declared, String ifaceFqcn,
            Map<String, TypeEntry> typeTable, Map<String, BeanDefinition> beans,
            Map<String, List<String>> implementations, int line) {
        List<BeanDefinition> candidates = implementations.getOrDefault(ifaceFqcn, List.of()).stream()
                .map(beans::get)
                .toList();

        if (candidates.isEmpty()) {
            return new BeanEdge(fieldName, declared, ifaceFqcn, BeanEdge.STATUS_UNRESOLVED,
                    null, BeanEdge.REASON_NO_IMPLEMENTATION_FOUND, null, List.of(), line);
        }

        // @Qualifier on the site beats everything, per Spring semantics.
        Optional<String> qualifier = annotationStringValue(site, "Qualifier");
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

    private static Optional<String> stereotypeOf(ClassOrInterfaceDeclaration decl, Set<String> customStereotypes) {
        for (String stereotype : STEREOTYPES) {
            if (hasAnnotation(decl, stereotype)) {
                return Optional.of(stereotype);
            }
        }
        return decl.getAnnotations().stream()
                .map(BeanIndex::simpleNameOf)
                .filter(customStereotypes::contains)
                .findFirst();
    }

    /**
     * The Spring Data base this interface is built on, if any — followed transitively
     * through scanned interfaces, because real codebases insert project-local base
     * interfaces between their repositories and Spring Data.
     */
    private static Optional<String> repositoryBaseOf(
            ClassOrInterfaceDeclaration decl, CompilationUnit cu,
            Map<String, TypeEntry> typeTable, Set<String> visited) {
        for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
            if (REPOSITORY_BASES.contains(extended.getNameAsString())) {
                return Optional.of(extended.getNameAsString());
            }
        }
        for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
            Optional<TypeEntry> parent = entryOf(extended, cu, typeTable);
            if (parent.isEmpty() || !parent.get().decl().isInterface()) {
                continue;
            }
            String parentFqcn = parent.get().decl().getFullyQualifiedName().orElse(null);
            if (parentFqcn == null || !visited.add(parentFqcn)) {
                continue;
            }
            Optional<String> base = repositoryBaseOf(
                    parent.get().decl(), parent.get().cu(), typeTable, visited);
            if (base.isPresent()) {
                return base;
            }
        }
        return Optional.empty();
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

    /**
     * One bean depending (certainly or possibly) on a queried type.
     *
     * @param bean the depending bean
     * @param edge its dependency site that references the queried type
     * @param via  how the reference was made: {@link #VIA_TARGET} (resolved to it),
     *             {@link #VIA_DECLARED_TYPE} (field is declared as it), or
     *             {@link #VIA_CANDIDATE} (it is one of an unresolved site's candidates —
     *             a possible dependent, reported rather than hidden)
     */
    public record Dependent(BeanDefinition bean, BeanEdge edge, String via) {
        public static final String VIA_TARGET = "target";
        public static final String VIA_DECLARED_TYPE = "declared-type";
        public static final String VIA_CANDIDATE = "candidate";
    }

    /**
     * FQCNs matching {@code className} among beans and declared dependency types (so
     * interfaces like a service's contract are queryable even when they are not beans):
     * an exact FQCN match wins alone; otherwise all simple-name matches.
     */
    public List<String> findTypeByName(String className) {
        java.util.stream.Stream<String> known = java.util.stream.Stream.concat(
                beansByFqcn.keySet().stream(),
                dependenciesByFqcn.values().stream()
                        .flatMap(d -> d.edges().stream())
                        .map(BeanEdge::declaredTypeFqcn)
                        .filter(java.util.Objects::nonNull));
        List<String> candidates = known.distinct().sorted().toList();
        if (candidates.contains(className)) {
            return List.of(className);
        }
        String suffix = "." + className;
        return candidates.stream().filter(f -> f.endsWith(suffix)).toList();
    }

    /**
     * Beans whose dependency edges reference this exact FQCN, in order: resolved to it,
     * declared as it, then unresolved sites listing it as a candidate.
     */
    public List<Dependent> dependentsOf(String fqcn) {
        List<Dependent> viaTarget = new ArrayList<>();
        List<Dependent> viaDeclared = new ArrayList<>();
        List<Dependent> viaCandidate = new ArrayList<>();
        for (BeanDependencies deps : dependenciesByFqcn.values()) {
            for (BeanEdge edge : deps.edges()) {
                if (edge.target() != null && edge.target().fqcn().equals(fqcn)) {
                    viaTarget.add(new Dependent(deps.bean(), edge, Dependent.VIA_TARGET));
                } else if (fqcn.equals(edge.declaredTypeFqcn())) {
                    viaDeclared.add(new Dependent(deps.bean(), edge, Dependent.VIA_DECLARED_TYPE));
                } else if (edge.candidates().stream().anyMatch(c -> c.fqcn().equals(fqcn))) {
                    viaCandidate.add(new Dependent(deps.bean(), edge, Dependent.VIA_CANDIDATE));
                }
            }
        }
        Comparator<Dependent> byBean = Comparator.comparing(d -> d.bean().fqcn());
        viaTarget.sort(byBean);
        viaDeclared.sort(byBean);
        viaCandidate.sort(byBean);
        List<Dependent> all = new ArrayList<>(viaTarget);
        all.addAll(viaDeclared);
        all.addAll(viaCandidate);
        return List.copyOf(all);
    }

    /** One resolved hop in a dependency traversal: {@code from.edge -> to}, at BFS depth. */
    public record TraceStep(BeanDefinition from, BeanEdge edge, BeanDefinition to, int depth) {
    }

    /** A dependency site a traversal could not continue through (unresolved edge). */
    public record BlockedSite(BeanDefinition bean, BeanEdge edge) {
    }

    /**
     * Everything reachable from one bean by following resolved edges (collection edges
     * fan out to every bound element).
     *
     * @param steps     resolved hops in BFS order, cycle-safe
     * @param terminals reached terminal beans (mappers / Spring Data repositories) —
     *                  the persistence boundary
     * @param blocked   unresolved sites encountered on the way, reported so the caller
     *                  knows where the trace is incomplete
     * @param truncated true when a depth limit stopped the traversal while unexpanded
     *                  resolved edges remained — the trace must never look complete
     *                  when it is not
     */
    public record TraceResult(List<TraceStep> steps, List<BeanDefinition> terminals,
                              List<BlockedSite> blocked, boolean truncated) {
    }

    /** Traverses resolved dependency edges breadth-first from the bean with this FQCN. */
    public TraceResult reachableFrom(String fqcn) {
        return reachableFrom(fqcn, Integer.MAX_VALUE);
    }

    /**
     * Traverses resolved dependency edges breadth-first, at most {@code maxDepth} hops deep.
     * A traversal the limit cut off is flagged {@code truncated} so it never looks complete.
     */
    public TraceResult reachableFrom(String fqcn, int maxDepth) {
        List<TraceStep> steps = new ArrayList<>();
        List<BeanDefinition> terminals = new ArrayList<>();
        List<BlockedSite> blocked = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<Map.Entry<String, Integer>> queue = new java.util.ArrayDeque<>();
        boolean truncated = false;

        if (!beansByFqcn.containsKey(fqcn)) {
            return new TraceResult(List.of(), List.of(), List.of(), false);
        }
        visited.add(fqcn);
        queue.add(Map.entry(fqcn, 0));

        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> current = queue.poll();
            BeanDependencies deps = dependenciesByFqcn.get(current.getKey());
            if (deps == null) {
                continue;
            }
            if (current.getValue() >= maxDepth) {
                // this bean's edges are beyond the limit; if any were resolved, the
                // caller is looking at an incomplete picture and must know
                if (deps.edges().stream().anyMatch(e -> BeanEdge.STATUS_RESOLVED.equals(e.status()))) {
                    truncated = true;
                }
                continue;
            }
            int depth = current.getValue() + 1;
            for (BeanEdge edge : deps.edges()) {
                if (BeanEdge.STATUS_UNRESOLVED.equals(edge.status())) {
                    blocked.add(new BlockedSite(deps.bean(), edge));
                    continue;
                }
                if (!BeanEdge.STATUS_RESOLVED.equals(edge.status())) {
                    continue;
                }
                List<BeanDefinition> targets = edge.target() != null
                        ? List.of(edge.target())
                        : edge.candidates();
                for (BeanDefinition target : targets) {
                    // a hop to an already-visited bean is still a real edge: report
                    // the step, just don't expand the target again
                    steps.add(new TraceStep(deps.bean(), edge, target, depth));
                    if (target.terminal() && terminals.stream()
                            .noneMatch(t -> t.fqcn().equals(target.fqcn()))) {
                        terminals.add(target);
                    }
                    if (visited.add(target.fqcn())) {
                        queue.add(Map.entry(target.fqcn(), depth));
                    }
                }
            }
        }
        return new TraceResult(List.copyOf(steps), List.copyOf(terminals), List.copyOf(blocked), truncated);
    }

    /** Count of unresolved dependency sites across all beans, grouped by reason. */
    public Map<String, Long> unresolvedInjectionCountByReason() {
        return dependenciesByFqcn.values().stream()
                .flatMap(d -> d.edges().stream())
                .filter(e -> BeanEdge.STATUS_UNRESOLVED.equals(e.status()))
                .collect(Collectors.groupingBy(BeanEdge::reason, Collectors.counting()));
    }
}
