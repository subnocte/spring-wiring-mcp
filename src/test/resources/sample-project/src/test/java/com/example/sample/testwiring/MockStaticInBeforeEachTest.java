package com.example.sample.testwiring;

import com.example.sample.beans.StripeGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** mockStatic() opened in @BeforeEach and closed in @AfterEach: frozen for every test method in the class. */
class MockStaticInBeforeEachTest {

    private MockedStatic<StripeGateway> mocked;

    @BeforeEach
    void freezeStripeGateway() {
        mocked = Mockito.mockStatic(StripeGateway.class);
    }

    @AfterEach
    void unfreezeStripeGateway() {
        mocked.close();
    }

    @Test
    void placeholder() {
    }
}
