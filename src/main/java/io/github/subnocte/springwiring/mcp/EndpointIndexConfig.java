package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Builds the {@link EndpointIndex} once at startup from the codebase pointed to by
 * {@code code.root} (bindable from the {@code CODE_ROOT} environment variable or the
 * {@code --code.root=...} command line argument via Spring's relaxed binding).
 */
@Configuration
public class EndpointIndexConfig {

    @Bean
    public EndpointIndex endpointIndex(@Value("${code.root}") String codeRoot) {
        Path root = Path.of(codeRoot);
        return EndpointIndex.forRoot(root);
    }
}
