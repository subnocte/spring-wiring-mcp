package com.example.sample.beans;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lombok {@code @RequiredArgsConstructor} injection: no constructor written in source,
 * final fields are the dependency sites. The {@code int} field must not appear as an edge.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AuditMapper auditMapper;
    private final GreetingService greetingService;
    private final int retries = 3;
}
