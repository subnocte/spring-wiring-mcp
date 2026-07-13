package com.example.sample.controller;

import org.springframework.web.bind.annotation.*;

/** RestController with a class-level base path and path-variable methods. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping
    public String listUsers() {
        return "list";
    }

    @GetMapping("/{id}")
    public String getUser(@PathVariable String id) {
        return "get";
    }

    @PostMapping
    public String createUser() {
        return "create";
    }

    @PutMapping("/{id}")
    public String updateUser(@PathVariable String id) {
        return "update";
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable String id) {
        return "delete";
    }
}
