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
        return html("static/index.html");
    }

    @GetMapping(value = {"/farmer", "/map", "/timeline", "/advisory", "/officer", "/intelligence"})
    public ResponseEntity<Resource> platformPages() {
        return html("static/portal.html");
    }

    private ResponseEntity<Resource> html(String path) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(new ClassPathResource(path));
    }
}
