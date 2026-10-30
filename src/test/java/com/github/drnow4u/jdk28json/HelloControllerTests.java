package com.github.drnow4u.jdk28json;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HelloController.class)
class HelloControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void helloPostWithNameInBodyReturnsPersonalizedGreeting() throws Exception {
        mockMvc.perform(post("/hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Spring\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Hello, Spring!"));
    }

    @Test
    void helloPostWithoutNameInBodyReturnsDefaultGreeting() throws Exception {
        mockMvc.perform(post("/hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Hello, World!"));
    }

    @Test
    void helloPostWithMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void helloPostWithBodyOverLimitReturnsPayloadTooLarge() throws Exception {
        mockMvc.perform(post("/hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void helloPostWithNonStringNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":42}"))
                .andExpect(status().isBadRequest());
    }

}
