package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Depends on a @Bean-method bean, not a stereotype-annotated class. */
@Service
public class PaymentAuditor {

    @Autowired
    private RateLimiter rateLimiter;
}
