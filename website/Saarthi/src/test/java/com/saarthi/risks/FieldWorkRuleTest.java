package com.saarthi.risks;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIELD_HIGH parity with Python {@code src/risk/field_work.py::classify_3d}.
 * Frozen rule: wet iff &gt;= 1.0 mm; HIGH iff &gt;= 2 wet days in D+1..D+3.
 * Python MODERATE (exactly 1 wet day) maps to LOW here — the validated
 * condition is HIGH-only — with the 1-wet-day evidence kept in the reason.
 */
class FieldWorkRuleTest {

    @Test
    void methodVersionPinned() {
        assertEquals("field_work_v1", FieldWorkRule.METHOD_VERSION);
        assertEquals(1.0, FieldWorkRule.WET_MM);
        assertEquals(2, FieldWorkRule.HIGH_MIN_WET_DAYS);
    }

    @Test
    void checkpointVectors() {
        assertEquals(FieldWorkRule.Category.LOW,
                FieldWorkRule.assess(List.of(0.0, 0.0, 0.0)).category()); // [0,0,0] LOW
        assertEquals(FieldWorkRule.Category.LOW,
                FieldWorkRule.assess(List.of(1.0, 0.0, 0.0)).category()); // [1,0,0] LOW
        assertEquals(FieldWorkRule.Category.HIGH,
                FieldWorkRule.assess(List.of(1.0, 1.0, 0.0)).category()); // [1,1,0] HIGH
        assertEquals(FieldWorkRule.Category.HIGH,
                FieldWorkRule.assess(List.of(1.0, 1.0, 0.0)).category()); // [1.0,1.0,0] HIGH
        assertEquals(FieldWorkRule.Category.LOW,
                FieldWorkRule.assess(List.of(0.99, 1.0, 0.0)).category()); // [0.99,1.0,0] LOW
        assertEquals(FieldWorkRule.Category.HIGH,
                FieldWorkRule.assess(List.of(10.0, 20.0, 0.0)).category()); // [10,20,0] HIGH
    }

    @Test
    void exactOneMmIsWet() {
        assertTrue(FieldWorkRule.isWet(1.00));
        assertFalse(FieldWorkRule.isWet(0.99));
        assertFalse(FieldWorkRule.isWet(null), "missing is never wet");
    }

    @Test
    void nullNeverBecomesZero() {
        FieldWorkRule.Assessment a =
                FieldWorkRule.assess(Arrays.asList(5.0, 5.0, null));
        assertEquals(FieldWorkRule.Category.UNAVAILABLE, a.category(),
                "missing day must not be treated as dry (NO DATA != NO RISK)");
        assertNull(a.wetDays());
        assertEquals(List.of("FIELD_UNAVAILABLE"), a.reasonCodes());
        assertEquals(FieldWorkRule.Category.UNAVAILABLE,
                FieldWorkRule.assess(null).category());
        assertEquals(FieldWorkRule.Category.UNAVAILABLE,
                FieldWorkRule.assess(List.of(1.0, 1.0)).category(),
                "wrong window size is unavailable, never a verdict");
    }

    @Test
    void evidenceAndReasons() {
        FieldWorkRule.Assessment high = FieldWorkRule.assess(List.of(2.0, 0.0, 3.5));
        assertEquals(2, high.wetDays());
        assertEquals(3.5, high.maxMm());
        assertEquals(List.of(2.0, 0.0, 3.5), high.dailyMm());
        assertEquals(List.of("FIELD_WET_DAYS_2OF3"), high.reasonCodes());

        FieldWorkRule.Assessment one = FieldWorkRule.assess(List.of(0.0, 1.5, 0.0));
        assertEquals(FieldWorkRule.Category.LOW, one.category());
        assertEquals(List.of("FIELD_WET_DAY_1OF3"), one.reasonCodes());

        FieldWorkRule.Assessment none = FieldWorkRule.assess(List.of(0.0, 0.2, 0.0));
        assertEquals(FieldWorkRule.Category.LOW, none.category());
        assertEquals(List.of("FIELD_NO_WET_DAYS"), none.reasonCodes());
    }

    @Test
    void parityWithPythonTierMapping() {
        // Python HIGH -> Java HIGH; Python MODERATE/LOW -> Java LOW (validated condition is HIGH-only).
        assertEquals(FieldWorkRule.Category.HIGH,
                FieldWorkRule.assess(List.of(1.0, 1.0, 1.0)).category());
        assertEquals(FieldWorkRule.Category.LOW,
                FieldWorkRule.assess(List.of(0.0, 0.0, 9.9)).category());
    }
}
