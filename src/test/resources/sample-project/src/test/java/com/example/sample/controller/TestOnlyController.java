package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives under src/test — a test fixture of the analyzed project itself,
 * not a production endpoint, so it must never be indexed.
 */
@RestController
public class TestOnlyController {

    @GetMapping("/test-only")
    public String testOnly() {
        return "test";
    }
}
