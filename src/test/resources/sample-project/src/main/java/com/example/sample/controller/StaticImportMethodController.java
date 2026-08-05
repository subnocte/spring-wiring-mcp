package com.example.sample.controller;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * method attribute given as statically imported RequestMethod constants. The PUT name is
 * deliberately shadowed by a same-class String constant: the analysis must not read
 * "method = PUT" as HTTP PUT when PUT resolves to something else in scope.
 */
@RestController
@RequestMapping("/si")
public class StaticImportMethodController {

    static final String PUT = "/shadowed-constant";

    @RequestMapping(value = "/echo", method = GET)
    public String echo() {
        return "echo";
    }

    @RequestMapping(value = "/multi", method = {GET, POST})
    public String multi() {
        return "multi";
    }

    @RequestMapping(value = "/shadow", method = PUT)
    public String shadow() {
        return "shadow";
    }
}
