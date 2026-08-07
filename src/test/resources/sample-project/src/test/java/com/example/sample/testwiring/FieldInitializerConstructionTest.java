package com.example.sample.testwiring;

import com.example.sample.beans.MockGateway;
import com.example.sample.beans.PaymentGateway;
import com.example.sample.beans.StripeGateway;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Field-initializer forms of test-double/real-subject construction, without any Mockito
 * annotation at all: {@code = mock(X.class)}, {@code = spy(new X())}, and a bare
 * {@code = new X()} real subject.
 */
class FieldInitializerConstructionTest {

    private final PaymentGateway mockedGateway = mock(MockGateway.class);

    private final PaymentGateway spiedGateway = spy(new StripeGateway());

    private final StripeGateway realSubject = new StripeGateway();

    @Test
    void placeholder() {
    }
}
