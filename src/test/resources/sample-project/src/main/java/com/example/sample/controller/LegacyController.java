package com.example.sample.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;

/** Plain @Controller (not @RestController) using method-level @RequestMapping(method=...). */
@Controller
@RequestMapping("/legacy")
public class LegacyController {

    @ResponseBody
    @RequestMapping(value = "/ping", method = RequestMethod.GET)
    public String ping() {
        return "pong";
    }

    @ResponseBody
    @RequestMapping(value = "/multi", method = {RequestMethod.GET, RequestMethod.POST})
    public String multi() {
        return "multi";
    }

    @ResponseBody
    @RequestMapping("/any")
    public String any() {
        return "any-method";
    }
}
