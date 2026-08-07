package com.example.sample.testwiring;

import com.example.sample.beans.StripeGateway;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** mockStatic() scoped to a single @Test method: the frozen type is torn down when the try-with-resources block ends. */
class MockStaticInMethodTest {

    @Test
    void freezesStripeGatewayForThisTestOnly() {
        try (MockedStatic<StripeGateway> mocked = Mockito.mockStatic(StripeGateway.class)) {
            mocked.verifyNoInteractions();
        }
    }
}
