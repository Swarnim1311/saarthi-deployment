package com.saarthi.controller;

import com.saarthi.service.policy.PibPolicySource;
import com.saarthi.service.policy.PolicySourceItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pib-test")
public class PibTestController {

    private final PibPolicySource pibPolicySource;

    public PibTestController(PibPolicySource pibPolicySource) {
        this.pibPolicySource = pibPolicySource;
    }

    @GetMapping
    public List<PolicySourceItem> getPibItems() {
        return pibPolicySource.fetch();
    }
}