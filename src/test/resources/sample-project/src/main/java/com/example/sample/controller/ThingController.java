package com.example.sample.controller;

import com.example.sample.api.ThingsApi;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementation class-level @RequestMapping takes precedence over the interface's
 * (Spring does not concatenate them): endpoints live under /v2, not /v1.
 */
@RestController
@RequestMapping("/v2")
public class ThingController implements ThingsApi {

    @Override
    public String getThing(String id) {
        return "thing";
    }

    @Override
    public String createThing() {
        return "created";
    }
}
