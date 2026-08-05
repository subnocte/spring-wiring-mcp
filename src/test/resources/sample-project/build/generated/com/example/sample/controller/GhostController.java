package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives under build/ — a generated/copied source that must never be indexed:
 * indexing it would report an endpoint that does not exist in src/main.
 */
@RestController
public class GhostController {

    @GetMapping("/ghost")
    public String ghost() {
        return "boo";
    }
}
