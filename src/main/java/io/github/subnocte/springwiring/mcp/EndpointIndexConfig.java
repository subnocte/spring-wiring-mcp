package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Builds the {@link CodeIndexes} holder at startup from the codebase pointed to by
 * {@code code.root} (bindable from the {@code CODE_ROOT} environment variable or the
 * {@code --code.root=...} command line argument via Spring's relaxed binding). The
 * holder rebuilds its indexes whenever the scanned sources change on disk.
 */
@Configuration
public class EndpointIndexConfig {

    @Bean
    public CodeIndexes codeIndexes(@Value("${code.root}") String codeRoot) {
        return CodeIndexes.forRoot(Path.of(codeRoot));
    }
}
