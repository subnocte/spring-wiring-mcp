package io.github.subnocte.springwiring.bean;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory index of Spring beans and their field-level dependency edges, built once
 * from the scanned sources. Resolution follows the same contract as the endpoint index:
 * every site that cannot be resolved statically is reported with a reason, never guessed.
 */
public final class BeanIndex {

    private BeanIndex() {
    }

    /** Scans {@code root} recursively and builds the bean index. */
    public static BeanIndex forRoot(Path root) {
        return new BeanIndex();
    }

    /** Every bean in the universe, sorted by FQCN. */
    public List<BeanDefinition> allBeans() {
        return List.of();
    }

    /**
     * Beans matching {@code className}: an exact FQCN match wins alone; otherwise all
     * beans whose simple name equals it (possibly several).
     */
    public List<BeanDefinition> findByName(String className) {
        return List.of();
    }

    /** Dependency edges of the bean with this exact FQCN; empty if it is not a bean. */
    public Optional<BeanDependencies> dependenciesOf(String fqcn) {
        return Optional.empty();
    }

    /** Count of unresolved dependency sites across all beans, grouped by reason. */
    public Map<String, Long> unresolvedInjectionCountByReason() {
        return Map.of();
    }
}
