package com.example.sample.controller;

import com.external.generated.ExternalApi;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mimics an OpenAPI-generator setup: the interface carrying all mappings is produced at
 * build time and does not exist in the repository, so the index must self-report it.
 */
@RestController
@RequestMapping("/api")
public class GeneratedStubController implements ExternalApi {
}
