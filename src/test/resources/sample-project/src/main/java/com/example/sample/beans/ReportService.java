package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Exercises three distinct unresolved reasons: multiple candidates, conditional
 * candidates, and no implementation found.
 */
@Service
public class ReportService {

    @Autowired
    private ExportFormat exportFormat;

    @Autowired
    private CacheProvider cacheProvider;

    @Autowired
    private SignatureVerifier signatureVerifier;
}
