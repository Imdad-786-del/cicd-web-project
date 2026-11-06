package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelloServletTest {
    @Test
    void greetsByName() {
        assertEquals("Hello, Sam!", HelloServlet.greeting("Sam"));
    }

    @Test
    void defaultsToWorld() {
        assertEquals("Hello, World!", HelloServlet.greeting(null));
    }
}
