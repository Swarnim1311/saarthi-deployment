package com.saarthi.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class WebViewController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource home() {
        return new ClassPathResource("static/index.html");
    }

    @GetMapping(value = {"/farmer", "/map", "/timeline", "/policy-alerts", "/advisory", "/officer", "/intelligence"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource platformPages() {
        return new ClassPathResource("static/portal.html");
    }
}
