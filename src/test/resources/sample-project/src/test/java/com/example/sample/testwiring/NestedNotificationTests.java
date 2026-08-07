package com.example.sample.testwiring;

import com.example.sample.beans.NotificationService;
import com.example.sample.beans.PaymentAuditor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * tap_manage-shaped structure: an outer @Mock/@InjectMocks pair whose scope covers every
 * @Nested inner test class, plus a @Nested class that declares its own additional @Mock.
 * The outer fields are recorded at the top level (nestedScope=null); they still apply
 * inside ValidateRegistrationFormTests, matching JUnit5's real @Nested semantics.
 */
@ExtendWith(MockitoExtension.class)
class NestedNotificationTests {

    @Mock
    private PaymentAuditor auditor;

    @InjectMocks
    private NotificationService subject;

    @Test
    void outerLevelTest() {
    }

    @Nested
    class ValidateRegistrationFormTests {

        @Mock
        private PaymentAuditor nestedOnlyAuditor;

        @Test
        void nestedLevelTest() {
        }
    }
}
