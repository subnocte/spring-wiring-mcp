package com.example.sample.beans;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Primary implementation of {@link PaymentGateway}. */
@Service
@Primary
public class StripeGateway implements PaymentGateway {
}
