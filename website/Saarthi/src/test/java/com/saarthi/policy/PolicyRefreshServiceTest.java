package com.saarthi.policy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh behavior: official items become verified-approved alerts, duplicates
 * are skipped, empty/unreachable sources report honestly, and rapid repeats
 * are throttled. No network except the intentionally-invalid URL case.
 */
class PolicyRefreshServiceTest {

    private static PolicySource stubSource(String name, List<PolicySourceItem> items) {
        return new PolicySource() {
            @Override public String getSourceName() { return name; }

            @Override public List<PolicySourceItem> fetch() { return items; }
        };
    }

    private static PolicySourceItem item(String title, String url, String category) {
        PolicySourceItem item =
                new PolicySourceItem(title, "summary", "PIB", url, LocalDate.of(2026, 9, 20));
        item.setCategory(category);
        return item;
    }

    private static PolicyAlertRepository emptyRepo() {
        PolicyAlertRepository repo = mock(PolicyAlertRepository.class);
        when(repo.findByExternalId(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    @Test
    void officialItemsAreStoredVerifiedAndApproved() {
        PolicyAlertRepository repo = emptyRepo();
        PolicyRefreshService service = new PolicyRefreshService(repo,
                List.of(stubSource("PIB", List.of(
                        item("PM KISAN instalment for farmers",
                                "https://pib.gov.in/press-release/1", "Scheme")))));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        assertEquals("success", out.get("status"));
        assertEquals(1, out.get("saved"));
        org.mockito.ArgumentCaptor<PolicyAlert> saved =
                org.mockito.ArgumentCaptor.forClass(PolicyAlert.class);
        verify(repo).save(saved.capture());
        assertTrue(saved.getValue().isApproved());
        assertEquals(PolicyRefreshService.VERIFIED_OFFICIAL,
                saved.getValue().getVerificationStatus());
        assertEquals(PolicyRefreshService.KIND_SCHEME, saved.getValue().getKind());
        assertEquals("https://pib.gov.in/press-release/1",
                saved.getValue().getSourceUrl(), "official URL preserved");
    }

    @Test
    void duplicatesAreSkipped() {
        PolicyAlertRepository repo = mock(PolicyAlertRepository.class);
        when(repo.findByExternalId(any())).thenReturn(Optional.of(new PolicyAlert()));
        PolicyRefreshService service = new PolicyRefreshService(repo,
                List.of(stubSource("PIB", List.of(
                        item("PM KISAN instalment for farmers",
                                "https://pib.gov.in/press-release/1", null)))));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        assertEquals("success", out.get("status"));
        assertEquals(0, out.get("saved"));
        assertEquals(1, out.get("total"));
        verify(repo, never()).save(any());
    }

    @Test
    void emptySourceReportsEmpty() {
        PolicyRefreshService service = new PolicyRefreshService(emptyRepo(),
                List.of(stubSource("PIB", List.of())));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        assertEquals("empty", out.get("status"));
        assertEquals(0, out.get("saved"));
    }

    @Test
    void throwingSourceReportsUnavailable() {
        PolicySource broken = new PolicySource() {
            @Override public String getSourceName() { return "PIB"; }

            @Override public List<PolicySourceItem> fetch() {
                throw new RuntimeException("connection refused");
            }
        };
        PolicyRefreshService service =
                new PolicyRefreshService(emptyRepo(), List.of(broken));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        // A source that throws yields no usable data: reported unavailable,
        // never silently swallowed as an empty success.
        assertEquals("unavailable", out.get("status"));
        assertEquals(0, out.get("saved"));
    }

    @Test
    void unreachablePibReportsUnavailable() {
        PibPolicySource pib =
                new PibPolicySource("https://example.invalid/rss", 1);
        PolicyRefreshService service =
                new PolicyRefreshService(emptyRepo(), List.of(pib));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        assertEquals("unavailable", out.get("status"));
        assertEquals(0, out.get("saved"));
    }

    @Test
    void rapidRepeatIsThrottled() {
        PolicyRefreshService service = new PolicyRefreshService(emptyRepo(),
                List.of(stubSource("PIB", List.of())));
        service.setThrottleMillis(60_000);
        service.refresh();
        Map<String, Object> second = service.refresh();
        assertEquals("throttled", second.get("status"));
    }

    @Test
    void ministryCategoryIsPreservedAndPridVariantsDedupe() {
        // Stateful stub: the second lookup for the same canonical id finds
        // the row saved by the first, exactly like the real repository.
        PolicyAlertRepository repo = mock(PolicyAlertRepository.class);
        java.util.Set<String> stored = new java.util.HashSet<>();
        when(repo.findByExternalId(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return stored.contains(id) ? Optional.of(new PolicyAlert()) : Optional.empty();
        });
        when(repo.save(any())).thenAnswer(inv -> {
            PolicyAlert alert = inv.getArgument(0);
            stored.add(alert.getExternalId());
            return alert;
        });
        PolicySourceItem rss = new PolicySourceItem(
                "Rabi procurement update", "s", "PIB",
                "https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2316100",
                LocalDate.of(2026, 9, 28));
        PolicySourceItem listing = new PolicySourceItem(
                "Union Minister Calls for Timely Procurement for Rabi Season 2026", "s", "PIB",
                "https://www.pib.gov.in/PressReleaseDetail.aspx?PRID=2316100",
                LocalDate.of(2026, 9, 28));
        listing.setCategory("Ministry of Agriculture & Farmers Welfare");
        PolicyRefreshService service = new PolicyRefreshService(repo,
                List.of(stubSource("PIB", List.of(rss, listing))));
        service.setThrottleMillis(0);
        Map<String, Object> out = service.refresh();
        assertEquals("success", out.get("status"));
        assertEquals(2, out.get("total"));
        // Same PRID under two URL shapes collapses to one stored row.
        org.mockito.ArgumentCaptor<PolicyAlert> saved =
                org.mockito.ArgumentCaptor.forClass(PolicyAlert.class);
        verify(repo, org.mockito.Mockito.times(1)).save(saved.capture());
        assertEquals("PIB:PRID:2316100", saved.getValue().getExternalId());
        // A repeated refresh stores nothing more.
        service.setThrottleMillis(0);
        Map<String, Object> repeat = service.refresh();
        assertEquals(0, repeat.get("saved"));
        verify(repo, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void ministryCategoryIsStoredOnSavedRow() {
        PolicyAlertRepository repo = emptyRepo();
        PolicySourceItem listing = new PolicySourceItem(
                "Union Minister Calls for Timely Procurement for Rabi Season 2026", "s", "PIB",
                "https://www.pib.gov.in/PressReleaseDetail.aspx?PRID=2316100",
                LocalDate.of(2026, 9, 28));
        listing.setCategory("Ministry of Agriculture & Farmers Welfare");
        PolicyRefreshService service = new PolicyRefreshService(repo,
                List.of(stubSource("PIB", List.of(listing))));
        service.setThrottleMillis(0);
        service.refresh();
        org.mockito.ArgumentCaptor<PolicyAlert> saved =
                org.mockito.ArgumentCaptor.forClass(PolicyAlert.class);
        verify(repo).save(saved.capture());
        assertEquals("Ministry of Agriculture & Farmers Welfare",
                saved.getValue().getCategory(), "real ministry attribution stored");
    }

    @Test
    void blankCategoryFallsBackToAgriculture() {
        PolicyAlertRepository repo = emptyRepo();
        PolicyRefreshService service = new PolicyRefreshService(repo,
                List.of(stubSource("PIB", List.of(
                        item("PM KISAN instalment for farmers",
                                "https://pib.gov.in/press-release/77", null)))));
        service.setThrottleMillis(0);
        service.refresh();
        org.mockito.ArgumentCaptor<PolicyAlert> saved =
                org.mockito.ArgumentCaptor.forClass(PolicyAlert.class);
        verify(repo).save(saved.capture());
        assertEquals("AGRICULTURE", saved.getValue().getCategory());
    }

    @Test
    void kindMappingUsesFeedCategoryOnly() {
        assertEquals(PolicyRefreshService.KIND_SCHEME,
                PolicyRefreshService.kindFor("Central Sector Scheme"));
        assertEquals(PolicyRefreshService.KIND_SCHEME,
                PolicyRefreshService.kindFor("किसान योजना"));
        assertEquals(PolicyRefreshService.KIND_POLICY,
                PolicyRefreshService.kindFor("Policy update"));
        assertEquals(PolicyRefreshService.KIND_ADVISORY,
                PolicyRefreshService.kindFor("Weather Advisory"));
        assertEquals(PolicyRefreshService.KIND_ADVISORY,
                PolicyRefreshService.kindFor("Public Notice"));
        assertEquals(PolicyRefreshService.KIND_UPDATE,
                PolicyRefreshService.kindFor("Press Release"));
        assertEquals(PolicyRefreshService.KIND_UPDATE,
                PolicyRefreshService.kindFor(null));
    }
}
