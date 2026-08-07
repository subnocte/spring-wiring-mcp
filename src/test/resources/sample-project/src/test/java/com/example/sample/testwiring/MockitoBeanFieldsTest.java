package com.example.sample.testwiring;

import com.example.sample.beans.GreetingService;
import com.example.sample.beans.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Newer @MockitoBean/@MockitoSpyBean context bean overrides (Spring Framework 6.2+). */
@SpringBootTest
class MockitoBeanFieldsTest {

    @MockitoBean
    private PaymentGateway gateway;

    @MockitoSpyBean
    private GreetingService greeter;

    @Test
    void placeholder() {
    }
}
