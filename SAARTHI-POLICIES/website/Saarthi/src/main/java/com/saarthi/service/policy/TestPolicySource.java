package com.saarthi.service.policy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@Profile("test-ingestion")
public class TestPolicySource implements PolicySource {

    @Override
    public String getSourceName() {
        return "TEST";
    }

    @Override
    public List<PolicySourceItem> fetch() {

        PolicySourceItem item = new PolicySourceItem(
                "PM Kisan Agriculture Support Scheme",
                "Test agriculture policy for Saarthi ingestion testing.",
                "TEST",
                "https://example.com/test-policy-001",
                LocalDate.now()
        );

        return List.of(item);
    }
}