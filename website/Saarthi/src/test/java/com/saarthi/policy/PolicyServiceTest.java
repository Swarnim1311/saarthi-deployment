package com.saarthi.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({PolicyAlertService.class, PolicyIngestionService.class, PibPolicySource.class})
class PolicyServiceTest {

    @Autowired
    private PolicyAlertRepository repository;

    @Autowired
    private PolicyAlertService service;

    private PolicyAlert saved(String externalId, boolean approved, LocalDate date) {
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
    void mapsEntityToResponse() {
        PolicyAlert a = saved("m1", true, LocalDate.of(2026, 9, 20));
        PolicyAlertResponse r = service.getLatestPolicies().get(0);
        assertEquals(a.getId(), r.getId());
        assertEquals("PM KISAN update m1", r.getTitle());
        assertEquals("PIB", r.getSource());
        assertEquals("PENDING", r.getVerificationStatus());
        assertEquals(LocalDate.of(2026, 9, 20), r.getPublishedDate());
    }

    @Test
    void latestExposesApprovedOnly() {
        saved("a1", true, LocalDate.of(2026, 9, 20));
        saved("p1", false, LocalDate.of(2026, 9, 21));

        assertEquals(1, service.getLatestPolicies().size());
        assertEquals("PM KISAN update a1", service.getLatestPolicies().get(0).getTitle());
        assertEquals(2, service.getAllPolicies().size());
        assertEquals("PM KISAN update p1", service.getAllPolicies().get(0).getTitle());
    }

    @Test
    void ingestionDedupesNormalisesAndStaysPending() {
        PolicyIngestionService local = new PolicyIngestionService(repository, List.of(
                new StubPolicySource(List.of(
                        new PolicySourceItem("  PM KISAN  ", " <b>summary</b> ", "pib",
                                "  https://pib.gov.in/press-release/9  ",
                                LocalDate.of(2026, 9, 20))))));

        assertEquals(1, local.ingestPolicies());
        assertEquals(0, local.ingestPolicies());
        assertEquals(1, repository.findAll().size());

        PolicyAlert stored = repository.findAll().get(0);
        assertEquals("PM KISAN", stored.getTitle());
        assertEquals("PIB:https://pib.gov.in/press-release/9", stored.getExternalId());
        assertFalse(stored.isApproved());
        assertEquals("PENDING", stored.getVerificationStatus());
        assertEquals("AGRICULTURE", stored.getCategory());
        assertTrue(service.getLatestPolicies().isEmpty());
    }

    @Test
    void ingestionSkipsBlankRows() {
        PolicyIngestionService local = new PolicyIngestionService(repository, List.of(
                new StubPolicySource(List.of(
                        new PolicySourceItem("   ", "s", "PIB", "https://pib.gov.in/x",
                                LocalDate.now()),
                        new PolicySourceItem("Title", "s", "PIB", "   ",
                                LocalDate.now())))));
        assertEquals(0, local.ingestPolicies());
        assertEquals(0, repository.count());
    }
}
