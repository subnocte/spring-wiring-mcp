package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uses Java 16+ syntax (instanceof pattern, text block). A parser stuck at an older
 * language level fails on this file — and a silently skipped file means silently
 * missing endpoints and beans.
 */
@RestController
public class ModernSyntaxController {

    @GetMapping("/modern")
    public String modern(Object input) {
        if (input instanceof String s) {
            return s;
        }
        return """
                modern
                """;
    }
}
