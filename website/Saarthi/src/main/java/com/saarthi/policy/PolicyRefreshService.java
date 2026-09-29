package com.saarthi.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public official-source refresh for the Government Policies page.
 *
 * <p><b>Why this exists.</b> The admin ingestion path stores rows as
 * {@code PENDING}/{@code approved=false} (nothing ever approves them) and is
 * token-gated, so the public approved-only feed is always empty. This service
 * pulls the same official {@link PolicySource} feeds but records provenance:
 * anything arriving from an allowlisted official source (PIB) is stored
 * {@code approved=true} with {@code verificationStatus=VERIFIED_OFFICIAL}.
 * Manually injected rows stay {@code PENDING} and stay hidden — the approval
 * semantics are preserved, not bypassed.
 *
 * <p><b>No injection surface.</b> The refresh takes no content from the
 * caller: it only re-reads the configured official feeds and dedups on
 * {@code SOURCE:url}. There is deliberately no way to submit a title, URL or
 * date through this path.
 */
@Service
public class PolicyRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PolicyRefreshService.class);

    static final String VERIFIED_OFFICIAL = "VERIFIED_OFFICIAL";
    static final String KIND_SCHEME = "Government scheme";
    static final String KIND_POLICY = "Policy announcement";
    static final String KIND_ADVISORY = "Advisory/notice";
    static final String KIND_UPDATE = "Official update";

    private final PolicyAlertRepository repository;
    private final List<PolicySource> sources;

    private volatile long throttleMillis = 30_000;
    private volatile Instant lastAttempt;

    public PolicyRefreshService(PolicyAlertRepository repository, List<PolicySource> sources) {
        this.repository = repository;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Test seam: minimum gap between live fetches. */
    void setThrottleMillis(long throttleMillis) {
        this.throttleMillis = Math.max(0, throttleMillis);
    }

    /**
     * Pull official feeds and store unseen items as verified official alerts.
     * Never throws for source trouble: that becomes {@code status=unavailable}
     * with zero saved, so the UI can say the official source is unreachable
     * instead of showing nothing or, worse, invented data.
     */
    @Transactional
    public synchronized Map<String, Object> refresh() {
        Instant now = Instant.now();
        if (lastAttempt != null
                && Duration.between(lastAttempt, now).toMillis() < throttleMillis) {
            return result("throttled", 0, 0, null,
                    "Refresh was requested too recently; showing saved policies.");
        }
        lastAttempt = now;

        List<PolicySourceItem> items = new ArrayList<>();
        boolean anyReachable = sources.isEmpty();
        String firstError = null;
        for (PolicySource source : sources) {
            try {
                List<PolicySourceItem> fetched = fetchFrom(source);
                if (fetched != null) {
                    anyReachable = true;
                    items.addAll(fetched);
                }
            } catch (RuntimeException e) {
                log.warn("Policy refresh: source {} failed ({}); continuing",
                        source.getSourceName(), e.getMessage());
                if (firstError == null) firstError = e.getMessage();
            }
        }
        if (!anyReachable) {
            log.warn("Policy refresh: official source unreachable ({}); keeping {} saved policies",
                    firstError, repository.count());
            return result("unavailable", 0, 0, null,
                    "The official policy source is currently unreachable"
                            + (firstError == null ? "." : ": " + firstError));
        }
        int saved = 0;
        int skipped = 0;
        for (PolicySourceItem item : items) {
            if (item.getTitle() == null || item.getTitle().isBlank()
                    || item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
                skipped++;
                continue;
            }
            String externalId = PolicyIngestionService.buildExternalId(item);
            if (repository.findByExternalId(externalId).isPresent()) {
                continue;
            }
            PolicyAlert alert = new PolicyAlert();
            alert.setTitle(item.getTitle().trim());
            alert.setSummary(item.getSummary() == null ? "" : item.getSummary().trim());
            alert.setSource(item.getSource());
            alert.setSourceUrl(item.getSourceUrl().trim());
            // Null when the feed carries no usable date: stored unknown, shown
            // as "Unavailable" — never replaced with the refresh date.
            alert.setPublishedDate(item.getPublishedDate());
            // Real source attribution when the feed carries it (the official
            // listing's ministry heading); otherwise the honest fallback.
            String category = item.getCategory() == null || item.getCategory().isBlank()
                    ? "AGRICULTURE" : item.getCategory().trim();
            alert.setCategory(category.length() > 100 ? category.substring(0, 100) : category);
            alert.setKind(kindFor(item.getCategory()));
            alert.setVerificationStatus(VERIFIED_OFFICIAL);
            alert.setApproved(true);
            alert.setExternalId(externalId);
            repository.save(alert);
            saved++;
        }
        // Never delete on external trouble: previously valid rows stay.
        log.info("Policy refresh: fetched={} skippedBlank={} saved={} statusSource={}",
                items.size(), skipped, saved, sourceNames());
        if (items.isEmpty()) {
            return result("empty", 0, 0, sourceNames(),
                    "The official source returned no farmer policy items right now.");
        }
        return result("success", saved, items.size(), sourceNames(),
                saved == 0 ? "Policies are already up to date."
                        : "Retrieved " + saved + " new official polic"
                                + (saved == 1 ? "y." : "ies."));
    }

    /**
     * Fetch one source, using the detailed outcome when the source offers it
     * (so "unreachable" is reported, not swallowed). A {@code null} return
     * means unreachable; an empty list means reachable-but-empty.
     */
    private static List<PolicySourceItem> fetchFrom(PolicySource source) {
        if (source instanceof PibPolicySource pib) {
            PibPolicySource.FetchOutcome outcome = pib.fetchDetailed();
            return outcome.reachable() ? outcome.items() : null;
        }
        return source.fetch();
    }

    private String sourceNames() {
        List<String> names = new ArrayList<>();
        for (PolicySource s : sources) names.add(s.getSourceName());
        return String.join(",", names);
    }

    /**
     * Display kind from the official feed's OWN category label only. The title
     * text is never classified: anything the feed does not explicitly label
     * stays "Official update" rather than a guessed scheme/policy split.
     */
    static String kindFor(String feedCategory) {
        if (feedCategory == null) return KIND_UPDATE;
        String c = feedCategory.toLowerCase(Locale.ROOT);
        // "yojana" is matched in Latin and Devanagari: the Hindi PIB feed
        // (Lang=1) labels schemes as "योजना", not the transliteration.
        if (c.contains("scheme") || c.contains("yojana") || c.contains("योजना")) {
            return KIND_SCHEME;
        }
        if (c.contains("advisor") || c.contains("notice")) return KIND_ADVISORY;
        if (c.contains("polic")) return KIND_POLICY;
        return KIND_UPDATE;
    }

    private static Map<String, Object> result(String status, int saved, int total,
            String source, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("saved", saved);
        out.put("total", total);
        out.put("source", source);
        out.put("message", message);
        return out;
    }
}
