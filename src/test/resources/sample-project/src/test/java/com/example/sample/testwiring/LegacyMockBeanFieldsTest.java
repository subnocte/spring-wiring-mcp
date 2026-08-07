package com.example.sample.testwiring;

import com.example.sample.beans.GreetingService;
import com.example.sample.beans.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

/** Classic @MockBean/@SpyBean context bean overrides, predating @MockitoBean/@MockitoSpyBean. */
@SpringBootTest
class LegacyMockBeanFieldsTest {

    @MockBean
    private PaymentGateway gateway;

    @SpyBean
    private GreetingService greeter;

    @Test
    void placeholder() {
    }
}
