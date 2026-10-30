package com.github.drnow4u.jdk28json;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uses the JDK 28 incubating JSON API ({@code jdk.incubator.json}) for
 * (de)serialization instead of delegating to Jackson, via
 * {@link JsonRecordHttpMessageConverter}.
 */
@RestController
public class HelloController {

    @PostMapping(value = "/hello", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public HelloResponse hello(@RequestBody HelloRequest request) {
        String name = request.name() != null ? request.name() : "World";
        return new HelloResponse("Hello, %s!".formatted(name));
    }

    public record HelloRequest(String name) {
    }

    public record HelloResponse(String message) {
    }
}
