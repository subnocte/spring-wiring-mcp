package com.example.sample.testwiring;

import com.example.sample.controller.ThingController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

/** A @WebMvcTest slice scoped to one controller: exercises the slice/controllers=... capture. */
@WebMvcTest(controllers = ThingController.class)
class WebMvcSliceTest {

    @Test
    void placeholder() {
    }
}
