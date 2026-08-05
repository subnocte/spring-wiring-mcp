package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Setter injection: the field itself carries no {@code @Autowired}, only its setter does. */
@Service
public class SetterService {

    private GreetingService greetingService;

    @Autowired
    public void setGreetingService(GreetingService greetingService) {
        this.greetingService = greetingService;
    }
}
