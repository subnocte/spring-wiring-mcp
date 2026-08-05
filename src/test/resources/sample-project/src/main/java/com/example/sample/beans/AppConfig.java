package com.example.sample.beans;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public RateLimiter rateLimiter() {
        return new RateLimiter();
    }
}
