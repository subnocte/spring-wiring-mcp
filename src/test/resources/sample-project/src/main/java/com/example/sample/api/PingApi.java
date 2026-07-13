package com.example.sample.api;

import org.springframework.web.bind.annotation.GetMapping;

/** API interface with method-level mappings only (no class-level prefix). */
public interface PingApi {

    @GetMapping("/ping")
    String ping();
}
