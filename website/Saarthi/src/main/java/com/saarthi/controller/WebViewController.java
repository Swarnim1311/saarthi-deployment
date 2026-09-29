package com.saarthi.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebViewController {

    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    @GetMapping(value = "/")
    public ResponseEntity<Resource> home() {
        return html("static/auth.html");
    }

    @GetMapping(value = "/home")
    public ResponseEntity<Resource> landing() {
        return html("static/index.html");
    }

    @GetMapping(value = "/weather")
    public ResponseEntity<Resource> weather() {
        return html("static/weather.html");
    }

    /**
     * Cinematic sign-in page. Standalone (its own stylesheet and script) so the
     * authentication surface never inherits platform page code, and so no
     * platform endpoint has to change to serve it.
     */
    @GetMapping(value = { "/signin", "/sign-in", "/login" })
    public ResponseEntity<Resource> signIn() {
        return html("static/auth.html");
    }

    // NOTE: no dedicated /chat page — the assistant is the floating widget
    // (chat.js, mounted on every page). Backend chat/voice APIs unchanged.
    @GetMapping(value = {"/farmer", "/map", "/risk-map", "/timeline", "/forecast", "/advisory", "/officer", "/intelligence",
            "/climate", "/cropatlas", "/policy-alerts", "/policies"})
    public ResponseEntity<Resource> platformPages() {
        return html("static/portal.html");
    }

    private ResponseEntity<Resource> html(String path) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(new ClassPathResource(path));
    }
}
