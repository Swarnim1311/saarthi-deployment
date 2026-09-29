package com.saarthi.cropatlas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic block-level crop discovery over official FAO ECOCROP.
 *
 * <p>Pipeline: measured fingerprint → {@link EcocropQueryMapper} levels →
 * {@link EcocropSearchClient} → candidates. The candidate SET is decided
 * entirely by ECOCROP: there is no default crop list, no six-crop catalogue,
 * and no fallback. Whatever ECOCROP returns — one hit, twelve, or none — is
 * what the block gets (capped for display, see {@link #MAX_DISPLAY}).
 *
 * <p><b>What a candidate means.</b> Each candidate carries an
 * {@code FAO ECOCROP environmental match}: ECOCROP's containment search found
 * this species' absolute requirement ranges compatible with the queried block
 * dimensions. That is explicitly NOT a SAARTHI compatibility score — no score
 * is computed, because ECOCROP result lists publish no per-crop requirement
 * values to score against, and inventing them is forbidden. Candidates
 * therefore report <i>matched</i> dimensions (what was queried) and
 * <i>unavailable</i> dimensions (what was omitted), never a fabricated number.
 *
 * <p><b>Enrichment boundary.</b> Common names are fetched from
 * {@code cropView} for displayed candidates only (at most {@link #MAX_DISPLAY}
 * extra requests, each fail-soft). The 2,568-entry database is never walked.
 */
@Service
public class EcocropDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(EcocropDiscoveryService.class);

    /** Display cap, applied in ECOCROP's own result order (documented, deterministic). */
    static final int MAX_DISPLAY = 15;

    /**
     * Per-candidate common-name budget. Enrichment runs concurrently and each
     * candidate gets at most this long; a slow cropView answer degrades that
     * one card to its scientific name instead of stalling the whole page.
     */
    static final long NAME_BUDGET_SECONDS = 20;

    /** One dynamic candidate: identity from ECOCROP, honesty from SAARTHI. */
    public record EcocropCandidate(
            String scientificName,
            String commonName,
            List<String> altNames,
            String ecoportId,
            String cropViewUrl,
            List<String> matchedDimensions,
            List<String> unavailableDimensions,
            String killingTemp,
            String growingPeriod) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scientific_name", scientificName);
            m.put("common_name", commonName);
            // At most two alternates reach the UI; the remaining synonyms are
            // never rendered as a wall of text on the card.
            m.put("alt_names", altNames == null ? List.of() : altNames);
            m.put("crop_name", commonName != null ? commonName : scientificName);
            m.put("ecoport_id", ecoportId);
            m.put("source", EcocropSearchClient.SOURCE_NAME);
            m.put("source_title", EcocropSearchClient.SOURCE_TITLE);
            m.put("source_url", EcocropSearchClient.SOURCE_URL);
            m.put("crop_view_url", cropViewUrl);
            // Per-crop DESCRIPTION notes, quoted verbatim from this species'
            // own ECOCROP page — genuinely crop-specific, never borrowed.
            m.put("killing_temp", killingTemp);
            m.put("growing_period", growingPeriod);
            m.put("match_basis", "FAO ECOCROP environmental match");
            m.put("match_detail", "ECOCROP's Absolute-mode containment search found this "
                    + "species' absolute requirement ranges compatible with the queried block "
                    + "dimensions. Species-level environmental match — not a variety/cultivar "
                    + "recommendation, not a yield or profitability claim.");
            m.put("matched_dimensions", matchedDimensions == null ? List.of() : matchedDimensions);
            m.put("unavailable_dimensions",
                    unavailableDimensions == null ? List.of() : unavailableDimensions);
            m.put("suitability", "ecocrop_match");
            m.put("score_available", false);
            m.put("compatibility_score", null);
            return m;
        }
    }

    /** Outcome of one discovery run — including honest empty/unavailable states. */
    public record Discovery(
            List<EcocropCandidate> candidates,
            int totalFound,
            boolean available,
            String reason,
            List<String> dimensionsUsed,
            List<String> dimensionsOmitted,
            int levelsTried,
            boolean cached,
            Instant retrievedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", available);
            m.put("reason", reason);
            m.put("candidate_count", candidates == null ? 0 : candidates.size());
            m.put("total_found", totalFound);
            m.put("dimensions_queried", dimensionsUsed == null ? List.of() : dimensionsUsed);
            m.put("dimensions_unavailable",
                    dimensionsOmitted == null ? List.of() : dimensionsOmitted);
            m.put("query_levels_tried", levelsTried);
            m.put("cached", cached);
            m.put("retrieved_at", retrievedAt == null ? null : retrievedAt.toString());
            m.put("source", EcocropSearchClient.SOURCE_NAME);
            m.put("source_url", EcocropSearchClient.SOURCE_URL);
            return m;
        }
    }

    private final EcocropSearchClient client;
    private final EcocropQueryMapper mapper;
    private final CropAtlasService atlas;

    @Value("${saarthi.ecocrop.max-display:15}")
    private int maxDisplay = MAX_DISPLAY;

    @Autowired
    public EcocropDiscoveryService(EcocropSearchClient client, EcocropQueryMapper mapper,
            CropAtlasService atlas) {
        this.client = client;
        this.mapper = mapper;
        this.atlas = atlas;
    }

    /** Test seam: display cap without Spring. */
    void setMaxDisplay(int maxDisplay) { this.maxDisplay = maxDisplay; }

    /** Cached successful ECOCROP searches (for the catalog endpoint). */
    public int cacheSize() { return client.cacheSize(); }

    public long cacheTtlMinutes() { return client.cacheTtlMinutes(); }

    /**
     * Discover candidates for one fingerprint. Never throws for upstream
     * trouble: that becomes {@code available:false} with zero candidates.
     */
    public Discovery discover(EnvironmentalFingerprint fp) {
        List<EcocropQueryMapper.EcocropQuery> levels = mapper.levelsFor(fp, atlas);
        if (levels.isEmpty()) {
            return new Discovery(List.of(), 0, true,
                    "No queryable environmental dimension for this block: temperature, "
                            + "rainfall normal, soil pH, latitude and elevation are all "
                            + "unavailable. CropAtlas reports no candidates rather than guessing.",
                    List.of(), List.copyOf(EcocropQueryMapper.NEVER_SENT), 0, false, Instant.now());
        }
        int tried = 0;
        for (EcocropQueryMapper.EcocropQuery q : levels) {
            tried++;
            EcocropSearchClient.SearchResult res;
            try {
                res = client.search(q.params(), true, "");
            } catch (EcocropSearchClient.EcocropUnavailableException e) {
                log.warn("ECOCROP search failed ({}); reporting unavailable, no fallback",
                        e.reason());
                return new Discovery(List.of(), 0, false,
                        "FAO ECOCROP could not be reached (" + e.reason() + "). No substitute "
                                + "candidates are shown.",
                        q.dimensionsUsed(), withNeverSent(q.dimensionsOmitted()), tried, false,
                        Instant.now());
            } catch (RuntimeException e) {
                log.warn("ECOCROP search failed ({}); reporting unavailable, no fallback",
                        e.getMessage());
                return new Discovery(List.of(), 0, false,
                        "FAO ECOCROP could not be reached. No substitute candidates are shown.",
                        q.dimensionsUsed(), withNeverSent(q.dimensionsOmitted()), tried, false,
                        Instant.now());
            }
            if (res.hits().isEmpty()) continue;
            List<EcocropCandidate> out = new ArrayList<>();
            int cap = Math.max(1, Math.min(maxDisplay, MAX_DISPLAY));
            List<EcocropSearchClient.EcocropHit> shown =
                    res.hits().subList(0, Math.min(cap, res.hits().size()));
            // Detail (names + per-crop DESCRIPTION notes) is independent per
            // candidate: resolve concurrently with a per-candidate budget so
            // one slow cropView answer cannot stall the page. Order is
            // preserved by index.
            List<CompletableFuture<EcocropSearchClient.CropDetail>> detailFutures = new ArrayList<>();
            for (EcocropSearchClient.EcocropHit hit : shown) {
                final String ecoportId = hit.ecoportId();
                detailFutures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.fetchCropDetail(ecoportId);
                    } catch (RuntimeException e) {
                        log.debug("ECOCROP detail lookup failed ({}); using scientific name",
                                e.getMessage());
                        return EcocropSearchClient.CropDetail.empty();
                    }
                }));
            }
            for (int i = 0; i < shown.size(); i++) {
                EcocropSearchClient.EcocropHit hit = shown.get(i);
                EcocropSearchClient.CropDetail detail = EcocropSearchClient.CropDetail.empty();
                try {
                    EcocropSearchClient.CropDetail got =
                            detailFutures.get(i).get(NAME_BUDGET_SECONDS, TimeUnit.SECONDS);
                    if (got != null) detail = got;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | java.util.concurrent.ExecutionException
                        | java.util.concurrent.TimeoutException e) {
                    log.debug("ECOCROP detail lookup timed out; using scientific name");
                }
                List<String> names = detail.names();
                String common = names.isEmpty() ? null : names.get(0);
                List<String> alts = names.size() > 1
                        ? List.copyOf(names.subList(1, Math.min(3, names.size())))
                        : List.of();
                out.add(new EcocropCandidate(hit.scientificName(), common, alts,
                        hit.ecoportId(),
                        client.resolveCropViewUrl(hit.cropViewPath()), q.dimensionsUsed(),
                        withNeverSent(q.dimensionsOmitted()),
                        detail.killingTemp(), detail.growingPeriod()));
            }
            return new Discovery(List.copyOf(out), res.totalFound(), true,
                    out.size() < res.totalFound()
                            ? "Showing " + out.size() + " of " + res.totalFound()
                                    + " ECOCROP matches in ECOCROP result order."
                            : "All " + res.totalFound() + " ECOCROP matches shown.",
                    q.dimensionsUsed(), withNeverSent(q.dimensionsOmitted()), tried, res.cached(),
                    res.retrievedAt());
        }
        EcocropQueryMapper.EcocropQuery last = levels.get(levels.size() - 1);
        return new Discovery(List.of(), 0, true,
                "FAO ECOCROP returned no species for the available environmental dimensions "
                        + "(all " + tried + " query levels). No candidates are invented to fill the gap.",
                last.dimensionsUsed(), withNeverSent(last.dimensionsOmitted()), tried, false,
                Instant.now());
    }

    private static List<String> withNeverSent(List<String> omitted) {
        List<String> out = new ArrayList<>(omitted == null ? List.of() : omitted);
        for (String d : EcocropQueryMapper.NEVER_SENT) {
            if (!out.contains(d)) out.add(d);
        }
        return List.copyOf(out);
    }
}
