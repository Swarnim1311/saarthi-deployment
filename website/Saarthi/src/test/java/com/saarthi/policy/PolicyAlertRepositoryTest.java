package com.saarthi.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class PolicyAlertRepositoryTest {

    @Autowired
    private PolicyAlertRepository repository;

    private PolicyAlert alert(String externalId, boolean approved, LocalDate date) {
        PolicyAlert a = new PolicyAlert();
        a.setTitle("PM KISAN update " + externalId);
        a.setSummary("Official PIB summary");
        a.setSource("PIB");
        a.setSourceUrl("https://pib.gov.in/press-release/" + externalId);
        a.setPublishedDate(date);
        a.setVerificationStatus("PENDING");
        a.setApproved(approved);
        a.setExternalId("PIB:" + externalId);
        return repository.save(a);
    }

    @Test
    void dedupesOnExternalId() {
        alert("id-1", true, LocalDate.of(2026, 9, 20));
        Optional<PolicyAlert> found = repository.findByExternalId("PIB:id-1");
        assertTrue(found.isPresent());
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void approvedQueryExcludesPending() {
        alert("approved-1", true, LocalDate.of(2026, 9, 20));
        alert("pending-1", false, LocalDate.of(2026, 9, 21));

        List<PolicyAlert> approved = repository.findTop50ByApprovedTrueOrderByPublishedDateDesc();
        assertEquals(1, approved.size());
        assertTrue(approved.get(0).isApproved());

        List<PolicyAlert> all = repository.findTop50ByOrderByPublishedDateDesc();
        assertEquals(2, all.size());
        // Newest first.
        assertEquals(LocalDate.of(2026, 9, 21), all.get(0).getPublishedDate());
    }
}
