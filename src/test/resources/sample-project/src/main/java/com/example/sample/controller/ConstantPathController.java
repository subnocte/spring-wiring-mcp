package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mappings the static analysis cannot fully resolve: a constant-referenced path and a
 * wildcard pattern. Both must be reported as unresolved, while /const/ok stays indexed.
 */
@RestController
@RequestMapping("/const")
public class ConstantPathController {

    static final String LIST_PATH = "/list";

    @GetMapping(LIST_PATH)
    public String list() {
        return "list";
    }

    @GetMapping("/files/**")
    public String files() {
        return "files";
    }

    @GetMapping("/ok")
    public String ok() {
        return "ok";
    }
}
