package com.example.sample.controller;

import com.example.sample.api.PingApi;
import org.springframework.web.bind.annotation.RestController;

/** Controller whose mappings live entirely on an in-repo interface. */
@RestController
public class PingController implements PingApi {

    @Override
    public String ping() {
        return "pong";
    }
}
