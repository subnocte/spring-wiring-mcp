package com.example.sample.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RestController with a multi-segment, multi-variable path pattern. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    @GetMapping("/{orderId}/items/{itemId}")
    public String getItem(@PathVariable String orderId, @PathVariable String itemId) {
        return "item";
    }
}
