package com.example.sample.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** API interface with its own class-level prefix, overridden by the implementation's. */
@RequestMapping("/v1")
public interface ThingsApi {

    @GetMapping("/things/{id}")
    String getThing(String id);

    @PostMapping("/things")
    String createThing();
}
