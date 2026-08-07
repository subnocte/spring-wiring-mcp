package com.example.sample.testwiring;

import com.example.sample.beans.StripeGateway;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** mockStatic() assigned directly in a field initializer: frozen for the lifetime of each test instance. */
class MockStaticAsFieldTest {

    private final MockedStatic<StripeGateway> frozenGateway = Mockito.mockStatic(StripeGateway.class);

    @Test
    void placeholder() {
    }
}
