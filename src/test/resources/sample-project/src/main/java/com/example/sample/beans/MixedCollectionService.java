package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exercises collection-injection resolution: a resolvable {@code Set}, a resolvable
 * {@code Map<String, X>} (keyed by bean name), a {@code Map} with a non-String key
 * (stays unresolvable), and a {@code List} of an interface with no implementation.
 */
@Service
public class MixedCollectionService {

    @Autowired
    private Set<ExportFormat> formatSet;

    @Autowired
    private Map<String, ExportFormat> formatsByName;

    @Autowired
    private Map<Long, ExportFormat> formatsById;

    @Autowired
    private List<SignatureVerifier> verifiers;
}
