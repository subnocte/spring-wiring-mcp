package com.example.sample.testwiring;

import com.example.sample.beans.CheckoutService;
import com.example.sample.beans.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Canonical Mockito-extension unit test: one @Mock collaborator, one @InjectMocks subject. */
@ExtendWith(MockitoExtension.class)
class MockitoExtensionFieldsTest {

    @Mock
    private PaymentGateway gateway;

    @InjectMocks
    private CheckoutService subject;

    @Test
    void placeholder() {
    }
}
