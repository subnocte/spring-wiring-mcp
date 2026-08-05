package io.github.subnocte.springwiring.tx;

import io.github.subnocte.springwiring.scanner.SourceScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TransactionalIndex}, exercised purely against the AST layer. Fixtures
 * live under {@code src/test/resources/sample-project}, package {@code com.example.sample.beans}.
 */
class TransactionalIndexTest {

    private static final String PKG = "com.example.sample.beans.";

    private static TransactionalIndex index;

    @BeforeAll
    static void buildIndex() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                TransactionalIndexTest.class.getResource("/sample-project")).toURI());
        index = TransactionalIndex.build(SourceScanner.scan(root));
    }

    private static TransactionalIndex.ClassTransactions classNamed(String fqcn) {
        return index.of(fqcn).orElseThrow(() -> new AssertionError("no such class: " + fqcn));
    }

    private static TransactionalIndex.MethodTx methodNamed(
            TransactionalIndex.ClassTransactions transactions, String methodName) {
        return transactions.methods().stream()
                .filter(m -> m.methodName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such method: " + methodName));
    }

    @Test
    void methodLevelStatusIsReported() {
        TransactionalIndex.ClassTransactions transactions = classNamed(PKG + "OrderTxService");

        TransactionalIndex.MethodTx place = methodNamed(transactions, "place");
        assertThat(place.transactional()).isTrue();
        assertThat(place.source()).isEqualTo("method");

        TransactionalIndex.MethodTx validate = methodNamed(transactions, "validate");
        assertThat(validate.transactional()).isFalse();
        assertThat(validate.source()).isEqualTo("none");

        TransactionalIndex.MethodTx audit = methodNamed(transactions, "audit");
        assertThat(audit.transactional()).isTrue();
        assertThat(audit.source()).isEqualTo("method");

        TransactionalIndex.MethodTx notifyAll2 = methodNamed(transactions, "notifyAll2");
        assertThat(notifyAll2.transactional()).isFalse();

        TransactionalIndex.MethodTx secret = methodNamed(transactions, "secret");
        assertThat(secret.transactional()).isTrue();
        assertThat(secret.source()).isEqualTo("method");
        assertThat(secret.privateTransactionalWarning()).isTrue();
    }

    @Test
    void classLevelTransactionalCoversPublicMethods() {
        TransactionalIndex.ClassTransactions transactions = classNamed(PKG + "TxClassService");

        assertThat(transactions.classLevelTransactional()).isTrue();

        TransactionalIndex.MethodTx save = methodNamed(transactions, "save");
        assertThat(save.transactional()).isTrue();
        assertThat(save.source()).isEqualTo("class");

        TransactionalIndex.MethodTx load = methodNamed(transactions, "load");
        assertThat(load.transactional()).isTrue();
        assertThat(load.source()).isEqualTo("class");

        TransactionalIndex.MethodTx helper = methodNamed(transactions, "helper");
        assertThat(helper.transactional()).isFalse();
        assertThat(helper.source()).isEqualTo("none");
    }

    @Test
    void selfInvocationsAreReported() {
        TransactionalIndex.ClassTransactions transactions = classNamed(PKG + "OrderTxService");

        assertThat(transactions.selfInvocations())
                .extracting(s -> s.callerMethod() + "->" + s.calleeMethod())
                .containsExactlyInAnyOrder("place->audit", "notifyAll2->audit");
    }

    @Test
    void findClassByName() {
        assertThat(index.findClassByName(PKG + "OrderTxService")).hasSize(1);
        assertThat(index.findClassByName("OrderTxService")).hasSize(1);
        assertThat(index.findClassByName("NoSuchClass")).isEmpty();
    }
}
