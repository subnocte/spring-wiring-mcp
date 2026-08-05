package com.example.sample.beans;

import org.springframework.stereotype.Service;

/** Non-primary implementation of {@link PaymentGateway}, selected elsewhere via @Qualifier. */
@Service
public class MockGateway implements PaymentGateway {
}
