package com.example.sample.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Exercises qualifier resolution, collection injection, and a library type outside the
 * scanned sources. The static final field must not appear as an edge.
 */
@Service
public class CheckoutService {

    @Autowired
    @Qualifier("mockGateway")
    private PaymentGateway gateway;

    @Autowired
    private List<ExportFormat> formats;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String VERSION = "1";
}
