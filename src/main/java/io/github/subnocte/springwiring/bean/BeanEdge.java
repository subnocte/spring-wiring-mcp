package io.github.subnocte.springwiring.bean;

import java.util.List;

/**
 * One dependency site of a bean: a non-static instance field whose declared type may
 * resolve to another scanned bean. Fields are the dependency model on purpose — they
 * cover field {@code @Autowired}, Lombok-generated constructors, and hand-written
 * constructor injection without needing Lombok expansion or constructor analysis.
 *
 * <p>Exactly one of the three shapes holds:
 * <ul>
 *   <li>{@code status == STATUS_RESOLVED}: {@code kind} and {@code target} are set</li>
 *   <li>{@code status == STATUS_UNRESOLVED}: {@code reason} is set; {@code candidates}
 *       lists the contenders for {@code MULTIPLE_CANDIDATES}/{@code CONDITIONAL_CANDIDATES}</li>
 *   <li>{@code status == STATUS_NOT_A_SCANNED_BEAN}: the declared type lives outside the
 *       scanned sources (library type) — informational, not an error</li>
 * </ul>
 *
 * @param fieldName        name of the field
 * @param declaredType     type as written in source (may be a simple name)
 * @param declaredTypeFqcn resolved FQCN of the declared type, or null when it could not be
 *                         mapped into the scanned sources
 * @param lineNumber       line of the field declaration
 */
public record BeanEdge(
        String fieldName,
        String declaredType,
        String declaredTypeFqcn,
        String status,
        String kind,
        String reason,
        BeanDefinition target,
        List<BeanDefinition> candidates,
        int lineNumber
) {

    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_UNRESOLVED = "unresolved";
    public static final String STATUS_NOT_A_SCANNED_BEAN = "not-a-scanned-bean";

    /** The declared type is itself a scanned concrete bean class. */
    public static final String KIND_CONCRETE = "concrete";
    /** Interface with exactly one scanned implementation. */
    public static final String KIND_SINGLE_IMPLEMENTATION = "single-implementation";
    /** Multiple implementations; exactly one is {@code @Primary}. */
    public static final String KIND_PRIMARY = "primary";
    /** {@code @Qualifier} on the field matched a bean's name. */
    public static final String KIND_QUALIFIER = "qualifier";
    /** Terminal bean interface (MyBatis mapper / Spring Data repository). */
    public static final String KIND_TERMINAL = "terminal";

    /** Multiple implementations and neither {@code @Primary} nor a matching {@code @Qualifier} decides. */
    public static final String REASON_MULTIPLE_CANDIDATES = "multiple-candidates";
    /** The interface is in the scanned sources but nothing implements it there. */
    public static final String REASON_NO_IMPLEMENTATION_FOUND = "no-implementation-found";
    /** Collection/map injection ({@code List<X>}, {@code Map<String, X>}) is not resolved in v1. */
    public static final String REASON_COLLECTION_INJECTION = "collection-injection";
    /** A candidate carries {@code @Profile}/{@code @ConditionalOn...}: the winner is environment-dependent. */
    public static final String REASON_CONDITIONAL_CANDIDATES = "conditional-candidates";
}
