package com.example.sample.testwiring;

import com.example.sample.beans.StripeGateway;
import com.example.sample.testwiring.support.Mock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Two self-reporting cases in one fixture: a field annotated {@code @Mock} that is NOT
 * {@code org.mockito.Mock} (a same-named, differently-imported annotation), and a
 * {@code mockStatic(...)} call whose argument is not a class literal. Both must land in
 * {@code unresolved} rather than being guessed.
 */
class UnknownAnnotationAndUnresolvedLiteralTest {

    @Mock
    private StripeGateway gateway;

    @Test
    void mockStaticArgumentIsNotAClassLiteral() {
        StripeGateway instance = new StripeGateway();
        try (var mocked = Mockito.mockStatic(instance.getClass())) {
            mocked.verifyNoInteractions();
        }
    }
}
