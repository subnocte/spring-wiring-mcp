package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the MCP tool layer over the sample-project transactional index: ambiguous and
 * missing lookups must self-report, never fail silently.
 */
class TransactionToolsTest {

    private static TransactionTools tools;

    @BeforeAll
    static void setUp() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                TransactionToolsTest.class.getResource("/sample-project")).toURI());
        tools = new TransactionTools(CodeIndexes.forRoot(root));
    }

    @Test
    void bySimpleName() {
        var result = tools.transactionalBoundaries("OrderTxService");

        assertThat(result.found()).isTrue();
        assertThat(result.transactions().selfInvocations()).hasSize(2);
    }

    @Test
    void unknown() {
        var result = tools.transactionalBoundaries("NoSuchClass");

        assertThat(result.found()).isFalse();
        assertThat(result.error()).contains("No scanned class");
    }
}
