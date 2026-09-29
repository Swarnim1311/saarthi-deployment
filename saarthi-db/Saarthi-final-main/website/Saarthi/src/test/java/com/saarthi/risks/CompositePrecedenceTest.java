package com.saarthi.risks;

import com.saarthi.risks.CompositeRiskService.CompositeAssessment;
import com.saarthi.risks.CompositeRiskService.DryWatch;
import com.saarthi.risks.CompositeRiskService.Overall;
import com.saarthi.risks.CompositeRiskService.WatchState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Frozen composite_v1 precedence truth table (no I/O, no Spring).
 * Must all pass before any API/frontend work.
 */
class CompositePrecedenceTest {

    static FieldWorkRule.Assessment fieldHigh() {
        return FieldWorkRule.assess(List.of(5.0, 0.0, 5.0));
    }

    static FieldWorkRule.Assessment fieldLow() {
        return FieldWorkRule.assess(List.of(0.0, 0.0, 0.0));
    }

    static DryWatch activeWatch() {
        return new DryWatch(WatchState.ACTIVE, 7, 5, List.of("f_dry>=6"));
    }

    static DryWatch quietWatch() {
        return new DryWatch(WatchState.QUIET, 1, 0, List.of("no_dryspell_signal"));
    }

    static DryWatch unknownWatch() {
        return new DryWatch(WatchState.UNKNOWN, null, null, List.of("WATCH_UNKNOWN"));
    }

    @Test
    void incompleteForecastIsUnavailable() {
        CompositeAssessment a = CompositeRiskService.assess(
                FieldWorkRule.assess(Arrays.asList(5.0, null, 5.0)),
                activeWatch(), false, true);
        assertEquals(Overall.UNAVAILABLE, a.overall());
        assertEquals("FIELD_WORK_DISRUPTION", a.primaryConcern());
        assertEquals("LOW", CompositeRiskService.confidenceOf(a));
    }

    @Test
    void fieldHighDrivesHigh() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldHigh(), quietWatch(), false, false);
        assertEquals(Overall.HIGH, a.overall());
        assertEquals("FIELD_WORK_DISRUPTION", a.primaryConcern());
        assertEquals("MODERATE", CompositeRiskService.confidenceOf(a));
    }

    @Test
    void fieldHighPlusWatchIsStillHigh() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldHigh(), activeWatch(), false, true);
        assertEquals(Overall.HIGH, a.overall());
        assertEquals("FIELD_WORK_DISRUPTION", a.primaryConcern(),
                "watch must never override a validated HIGH");
    }

    @Test
    void watchOnlyIsModerateWithPendingFlag() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), activeWatch(), false, false);
        assertEquals(Overall.MODERATE, a.overall());
        assertEquals("DRY_SPELL_WATCH", a.primaryConcern());
        assertTrue(a.reasons().contains("PENDING_IFS_VALIDATION"));
        assertEquals("LOW", CompositeRiskService.confidenceOf(a),
                "unvalidated watch caps confidence at LOW");
    }

    @Test
    void unknownWatchWithFreshForecastIsLow() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), unknownWatch(), false, false);
        assertEquals(Overall.LOW, a.overall());
        assertEquals("NONE", a.primaryConcern());
        assertEquals("MODERATE", CompositeRiskService.confidenceOf(a));
    }

    @Test
    void staleForecastIsModerate() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), quietWatch(), true, false);
        assertEquals(Overall.MODERATE, a.overall());
        assertTrue(a.reasons().contains("STALE_FORECAST"));
        assertEquals("LOW", CompositeRiskService.confidenceOf(a));
    }

    @Test
    void stalePlusWatchHasExplicitReasons() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), activeWatch(), true, false);
        assertEquals(Overall.MODERATE, a.overall());
        assertEquals("DRY_SPELL_WATCH", a.primaryConcern(),
                "watch outranks stale in precedence, both reasons recorded");
        assertTrue(a.reasons().contains("STALE_FORECAST"));
        assertTrue(a.reasons().contains("PENDING_IFS_VALIDATION"));
    }

    @Test
    void freshQuietIsLow() {
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), quietWatch(), false, false);
        assertEquals(Overall.LOW, a.overall());
        assertEquals("MODERATE", CompositeRiskService.confidenceOf(a));
    }

    @Test
    void heavyNeverChangesSeverity() {
        for (Boolean heavy : Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)) {
            assertEquals(Overall.HIGH, CompositeRiskService.assess(
                    fieldHigh(), quietWatch(), false, heavy).overall());
            assertEquals(Overall.LOW, CompositeRiskService.assess(
                    fieldLow(), quietWatch(), false, heavy).overall());
            assertEquals(Overall.MODERATE, CompositeRiskService.assess(
                    fieldLow(), activeWatch(), false, heavy).overall());
        }
    }

    @Test
    void soilAndContextNeverChangeSeverity() {
        // assess() takes no context parameter by design: context cannot
        // influence severity. This test locks that structural guarantee.
        CompositeAssessment a = CompositeRiskService.assess(
                fieldLow(), quietWatch(), false, null);
        assertEquals(Overall.LOW, a.overall());
        java.util.Set<String> components = new java.util.HashSet<>();
        for (var rc : CompositeAssessment.class.getRecordComponents()) {
            components.add(rc.getName());
        }
        assertEquals(java.util.Set.of("overall", "primaryConcern", "reasons",
                "field", "watch", "heavy", "stale"), components,
                "assessment carries field/watch/heavy/stale only — no context channel");
    }

    @Test
    void methodVersionsPresent() {
        assertEquals("composite_v1", CompositeRiskService.COMPOSITE_METHOD_VERSION);
        assertEquals("field_work_v1", FieldWorkRule.METHOD_VERSION);
        assertEquals("phase4.0-frozen", com.saarthi.shadow.DrySpellRule.RULE_VERSION);
    }

    @Test
    void watchEvaluationIsNullSafe() {
        assertEquals(WatchState.UNKNOWN,
                CompositeRiskService.evaluateWatch(null, null).state());
        assertEquals(WatchState.UNKNOWN,
                CompositeRiskService.evaluateWatch(
                        Arrays.asList(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null),
                        List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
                        .state());
        // all-dry forecast + all-dry antecedent -> ACTIVE via f_dry>=6 arm
        DryWatch active = CompositeRiskService.evaluateWatch(
                List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertEquals(WatchState.ACTIVE, active.state());
        assertEquals(7, active.fDry());
        // all-wet antecedent + all-wet forecast -> QUIET
        DryWatch quiet = CompositeRiskService.evaluateWatch(
                List.of(9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9),
                List.of(9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9,
                        9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9,
                        9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9, 9.9));
        assertEquals(WatchState.QUIET, quiet.state());
    }

    @Test
    void heavyEvidenceIsStrictlyAboveP95() {
        assertEquals(Boolean.TRUE,
                CompositeRiskService.heavyEvidence(List.of(0.0, 25.0, 0.0), 21.151));
        assertEquals(Boolean.FALSE,
                CompositeRiskService.heavyEvidence(List.of(0.0, 21.151, 0.0), 21.151));
        assertNull(CompositeRiskService.heavyEvidence(List.of(0.0, 25.0, 0.0), null));
        assertNull(CompositeRiskService.heavyEvidence(Arrays.asList(0.0, null, 0.0), 21.151));
    }
}
