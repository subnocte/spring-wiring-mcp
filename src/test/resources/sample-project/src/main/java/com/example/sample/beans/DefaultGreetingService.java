package com.example.sample.beans;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/** Single implementation of {@link GreetingService}; Lombok all-args constructor injection. */
@Service
@AllArgsConstructor
public class DefaultGreetingService implements GreetingService {

    private PaymentGateway paymentGateway;
}
