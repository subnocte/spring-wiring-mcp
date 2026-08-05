package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint whose trace runs into {@link ReportService}'s unresolved (blocked) edges. */
@RestController
public class ReportController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/reports")
    public String list() {
        return "reports";
    }
}
