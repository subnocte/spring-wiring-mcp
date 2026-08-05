package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;

/** Bean made via the custom {@link UseCase} stereotype (directly @Component-annotated). */
@UseCase
public class GreetUseCase {

    @Autowired
    private GreetingService greetingService;
}
