package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.bean.BeanIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the MCP tool layer over the sample-project bean index: ambiguous and
 * missing lookups must self-report, never fail silently.
 */
class BeanGraphToolsTest {

    private static final String PKG = "com.example.sample.beans.";

    private static BeanGraphTools tools;

    @BeforeAll
    static void setUp() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                BeanGraphToolsTest.class.getResource("/sample-project")).toURI());
        BeanIndex beanIndex = BeanIndex.forRoot(root);
        tools = new BeanGraphTools(beanIndex);
    }

    @Test
    void resolvesBySimpleName() {
        var result = tools.beanDependencies("NotificationService");

        assertThat(result.found()).isTrue();
        assertThat(result.bean().fqcn()).isEqualTo(PKG + "NotificationService");
        assertThat(result.edges()).hasSize(2);
    }

    @Test
    void resolvesByFullyQualifiedName() {
        var result = tools.beanDependencies(PKG + "NotificationService");

        assertThat(result.found()).isTrue();
        assertThat(result.bean().fqcn()).isEqualTo(PKG + "NotificationService");
        assertThat(result.edges()).hasSize(2);
    }

    @Test
    void ambiguousSimpleNameIsReportedNotGuessed() {
        var result = tools.beanDependencies("Formatter");

        assertThat(result.found()).isFalse();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.error()).contains("fully qualified name");
    }

    @Test
    void unknownBeanNameIsReportedNotSilentlyEmpty() {
        var result = tools.beanDependencies("NoSuchBean");

        assertThat(result.found()).isFalse();
        assertThat(result.error()).contains("No scanned bean");
    }

    @Test
    void beanDependentsResolvesBySimpleNameToResolvedTargetEdge() {
        var result = tools.beanDependents("NotificationService");

        assertThat(result.found()).isTrue();
        assertThat(result.targetFqcn()).isEqualTo(PKG + "NotificationService");
        assertThat(result.dependents()).hasSize(1);
        assertThat(result.dependents().get(0).bean().fqcn()).isEqualTo(PKG + "NotificationController");
    }

    @Test
    void beanDependentsFindsMultipleDeclaredTypeSites() {
        var result = tools.beanDependents("PaymentGateway");

        assertThat(result.found()).isTrue();
        assertThat(result.dependents()).hasSize(2);
    }

    @Test
    void beanDependentsAmbiguousSimpleNameIsReportedNotGuessed() {
        var result = tools.beanDependents("Formatter");

        assertThat(result.found()).isFalse();
        assertThat(result.matches()).hasSize(2);
    }

    @Test
    void beanDependentsUnknownTypeNameIsReportedNotSilentlyEmpty() {
        var result = tools.beanDependents("NoSuchType");

        assertThat(result.found()).isFalse();
        assertThat(result.error()).contains("No scanned type");
    }
}
