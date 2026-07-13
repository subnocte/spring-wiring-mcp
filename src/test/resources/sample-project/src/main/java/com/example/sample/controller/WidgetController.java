package com.example.sample.controller;

import static com.example.sample.api.ApiPaths.WIDGET_BY_ID;

import com.example.sample.api.ApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paths referenced through constants in another class: class-level via qualified field
 * access, method-level via static import.
 */
@RestController
@RequestMapping(ApiPaths.WIDGETS)
public class WidgetController {

    @GetMapping
    public String list() {
        return "widgets";
    }

    @GetMapping(WIDGET_BY_ID)
    public String byId(@PathVariable String id) {
        return "widget";
    }
}
