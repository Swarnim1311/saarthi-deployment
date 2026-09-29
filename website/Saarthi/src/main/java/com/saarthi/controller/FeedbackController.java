package com.saarthi.controller;

import com.saarthi.model.Feedback;
import com.saarthi.repository.FeedbackRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FeedbackController {

    @Autowired
    private FeedbackRepository feedbackRepository;

    @PostMapping("/feedback")
    public ResponseEntity<Feedback> submitFeedback(@RequestBody Feedback feedback) {
        if (feedback.getSubmittedAt() == null) {
            feedback.setSubmittedAt(java.time.LocalDateTime.now());
        }
        Feedback saved = feedbackRepository.save(feedback);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/feedback")
    public ResponseEntity<List<Feedback>> getAllFeedback() {
        return ResponseEntity.ok(feedbackRepository.findAll());
    }
}
