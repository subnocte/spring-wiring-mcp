package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The variable pattern is deliberately declared BEFORE the literal one:
 * Spring resolves /items/special to the literal mapping regardless of
 * declaration order, and so must the index.
 */
@RestController
@RequestMapping("/items")
public class SpecificityController {

    @GetMapping("/{id}")
    public String byId(@PathVariable String id) {
        return "by-id";
    }

    @GetMapping("/special")
    public String special() {
        return "special";
    }
}
