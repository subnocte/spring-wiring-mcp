package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.index.TestIndexesRegistry;
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
 *   <li>{@link TestIndexesRegistry} is the test-wiring counterpart: same
 *       {@code spring-wiring.max-roots} cap (one shared notion of "how many roots can be
 *       open at once"), but built lazily — {@code testWiring}/{@code testDoubleUsage} are
 *       the only tools that touch it, so a session that never calls them never pays for
 *       parsing test sources
 *   <li>{@link RefMaterializer} turns a git ref into a plain directory under
 *       {@code java.io.tmpdir}, keeping up to {@code spring-wiring.max-ref-generations}
 *       materialized commits (LRU eviction beyond that)
 *   <li>{@link RootRefResolver} composes the production registry and the ref materializer
 *       into what the six production tools call; {@link TestWiringTools} additionally
 *       looks up {@link TestIndexesRegistry} for whatever directory that resolves to
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
    public TestIndexesRegistry testIndexesRegistry(
            @Value("${code.root}") String codeRoot,
            @Value("${spring-wiring.max-roots:" + CodeIndexesRegistry.DEFAULT_MAX_ROOTS + "}") int maxRoots) {
        return new TestIndexesRegistry(Path.of(codeRoot), maxRoots);
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
