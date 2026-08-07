package com.example.sample.testwiring;

import com.example.sample.beans.DefaultGreetingService;
import com.example.sample.beans.StripeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit5, no Mockito extension at all: the subject is real, constructed by hand in
 * @BeforeEach rather than declared with @InjectMocks. TestWiringIndex must still report it
 * as a realSubject (source="new") so constructor-injection-style tests aren't invisible.
 */
class BeforeEachConstructedSubjectTest {

    private DefaultGreetingService subject;

    @BeforeEach
    void setUp() {
        subject = new DefaultGreetingService(new StripeGateway());
    }

    @Test
    void placeholder() {
    }
}
