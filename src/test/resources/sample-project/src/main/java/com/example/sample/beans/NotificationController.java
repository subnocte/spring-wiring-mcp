package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** RestController with a single field @Autowired dependency. */
@RestController
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping("/notifications")
    public String list() {
        return "notifications";
    }
}
