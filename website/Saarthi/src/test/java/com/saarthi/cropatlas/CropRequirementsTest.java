package com.saarthi.cropatlas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CropAtlas requirement-catalogue loading and the honesty contract around it.
 */
class CropRequirementsTest {

    @Test
    void loadsShippedCatalogue() {
        CropRequirements r = new CropRequirements();
        assertFalse(r.isEmpty(), "shipped crop-requirements.json must load");
        assertTrue(r.all().size() >= 5, "expected a practical crop set, got " + r.all().size());
        assertEquals("cropatlas-req-v1", r.methodVersion());
        assertTrue(r.derivedFrom().contains("crop_reference.json"));
    }

    @Test
    void everyRequirementCarriesReferenceProvenance() {
        for (CropRequirements.Requirement req : new CropRequirements().all()) {
            assertNotNull(req.cropId());
            assertNotNull(req.displayName());
            assertTrue(req.sowStartDoy() > 0, req.cropId() + " sow window start must resolve");
            assertTrue(req.sowEndDoy() > 0, req.cropId() + " sow window end must resolve");
            assertTrue(req.waterNeedOrdinal() >= 0,
                    req.cropId() + " water_need_class must be ranked");
            assertFalse(req.sourceIds().isEmpty(),
                    req.cropId() + " must cite at least one source");
            assertFalse(req.referenceIds().isEmpty(),
                    req.cropId() + " must name the reference entries it is derived from");
        }
    }

    @Test
    void seasonAndSowWindowsAreInternallyConsistent() {
        for (CropRequirements.Requirement req : new CropRequirements().all()) {
            assertTrue(req.sowWindowLengthDays() > 0 && req.sowWindowLengthDays() <= 365,
                    req.cropId() + " sowing window length is implausible");
            assertTrue(req.durationDays() != null && req.durationDays() > 0,
                    req.cropId() + " must state a growing duration");
        }
    }

    @Test
    void unsatisfiedDimensionsAreDeclaredNotInvented() {
        Map<String, String> gaps = new CropRequirements().notAvailableDimensions();
        // The consulted reference states none of these, so they must be declared
        // absent rather than silently filled in.
        assertTrue(gaps.containsKey("temperature_range_c"));
        assertTrue(gaps.containsKey("soil_ph_range"));
        assertTrue(gaps.containsKey("soil_texture_class"));
        assertTrue(gaps.containsKey("quantitative_irrigation_mm"));
        for (String reason : gaps.values()) {
            assertNotNull(reason);
            assertFalse(reason.isBlank(), "every gap must carry an explanation");
        }
    }

    @Test
    void catalogueIsDeterministicallyOrderedAndAddressable() {
        CropRequirements r = new CropRequirements();
        List<String> first = r.all().stream().map(CropRequirements.Requirement::cropId).toList();
        List<String> second = new CropRequirements().all().stream()
                .map(CropRequirements.Requirement::cropId).toList();
        assertEquals(first, second, "catalogue order must be stable across loads");
        assertNotNull(r.byId("wheat"));
        assertNull(r.byId("definitely-not-a-crop"));
    }

    @Test
    void malformedCatalogueFailsSoftToEmptyRatherThanInventingCrops() {
        CropRequirements r = new CropRequirements("cropatlas/does-not-exist.json");
        assertTrue(r.isEmpty(), "a missing resource must yield no crops, not invented ones");
        assertEquals(0, r.all().size());
    }

    @Test
    void dayOfYearConversionFollowsTheExistingClimatologyWheel() {
        // Feb-29 → 60 and non-leap +1 from Mar-01, matching ClimatologyContext.
        assertEquals(60, CropRequirements.mmDdToDoy("02-29"));
        assertEquals(61, CropRequirements.mmDdToDoy("03-01"));
        assertEquals(31, CropRequirements.mmDdToDoy("01-31"));
        assertEquals(-1, CropRequirements.mmDdToDoy("13-01"));
        assertEquals(-1, CropRequirements.mmDdToDoy("nope"));
        assertFalse(CropRequirements.isMmDd("00-10"));
        assertFalse(CropRequirements.isMmDd("02-31x"));
    }
}
