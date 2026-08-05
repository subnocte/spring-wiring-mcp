package io.github.subnocte.springwiring.bean;

/**
 * A Spring bean discovered in the scanned sources.
 *
 * @param fqcn       fully qualified name of the bean class (or interface, for terminal beans)
 * @param stereotype simple name of the defining annotation ({@code Service}, {@code Component},
 *                   {@code Repository}, {@code Controller}, {@code RestController},
 *                   {@code Configuration}, {@code Bean} for factory methods, {@code Mapper} or
 *                   the extended repository interface's simple name for terminal beans)
 * @param filePath   absolute path of the defining source file
 * @param lineNumber line of the class/interface/method declaration
 * @param terminal   true for interfaces implemented by a framework at runtime (MyBatis mappers,
 *                   Spring Data repositories): injectable, but with no outgoing edges to analyze
 */
public record BeanDefinition(
        String fqcn,
        String stereotype,
        String filePath,
        int lineNumber,
        boolean terminal
) {

    /** {@code fqcn} without the package, for display and simple-name lookup. */
    public String simpleName() {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
