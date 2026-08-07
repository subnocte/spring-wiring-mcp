package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.ref.RefMaterializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the root/ref analysis stack from the codebase pointed to by {@code code.root}
 * (bindable from the {@code CODE_ROOT} environment variable or the {@code --code.root=...}
 * command line argument via Spring's relaxed binding):
 *
 * <ul>
 *   <li>{@link CodeIndexesRegistry} caches a {@code CodeIndexes} per analyzed directory,
 *       always including {@code code.root} as the default, up to
 *       {@code spring-wiring.max-roots} simultaneously (LRU eviction beyond that,
 *       default root never evicted)
 *   <li>{@link RefMaterializer} turns a git ref into a plain directory under
 *       {@code java.io.tmpdir}, keeping up to {@code spring-wiring.max-ref-generations}
 *       materialized commits (LRU eviction beyond that)
 *   <li>{@link RootRefResolver} composes the two into what every tool actually calls
 * </ul>
 */
@Configuration
public class EndpointIndexConfig {

    @Bean
    public CodeIndexesRegistry codeIndexesRegistry(
            @Value("${code.root}") String codeRoot,
            @Value("${spring-wiring.max-roots:" + CodeIndexesRegistry.DEFAULT_MAX_ROOTS + "}") int maxRoots) {
        return new CodeIndexesRegistry(Path.of(codeRoot), maxRoots);
    }

    @Bean
    public RefMaterializer refMaterializer(
            @Value("${spring-wiring.max-ref-generations:" + RefMaterializer.DEFAULT_MAX_GENERATIONS + "}")
            int maxRefGenerations) {
        Path refsBaseDir = Path.of(System.getProperty("java.io.tmpdir"), "spring-wiring-mcp", "refs");
        return new RefMaterializer(refsBaseDir, maxRefGenerations);
    }

    @Bean
    public RootRefResolver rootRefResolver(CodeIndexesRegistry registry, RefMaterializer refMaterializer) {
        return new RootRefResolver(registry, refMaterializer);
    }
}
