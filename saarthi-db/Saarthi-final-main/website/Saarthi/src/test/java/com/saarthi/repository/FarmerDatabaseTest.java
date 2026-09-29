package com.saarthi.repository;

import com.saarthi.model.CropPlanting;
import com.saarthi.model.Farm;
import com.saarthi.model.Farmer;
import com.saarthi.model.Feedback;
import com.saarthi.model.Panchayat;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.service.DistrictDataService;
import com.saarthi.service.FarmerAssignmentService;
import com.saarthi.service.FarmerCodeService;
import com.saarthi.service.PanchayatSeedService;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Application-database slice tests (H2, Hibernate-generated schema):
 * relationships, constraints, history semantics, farmer codes, seed
 * idempotency and Feedback compatibility. Database ON DELETE actions of
 * the real Flyway migration are covered separately in
 * {@code FarmerSchemaMigrationTest}.
 */
@DataJpaTest
class FarmerDatabaseTest {

    @Autowired
    PanchayatRepository panchayats;
    @Autowired
    PanchayatOfficialRepository officials;
    @Autowired
    FarmerRepository farmers;
    @Autowired
    FarmRepository farms;
    @Autowired
    CropPlantingRepository plantings;
    @Autowired
    FeedbackRepository feedbacks;
    @Autowired
    TestEntityManager tem;

    // ------------------------------------------------------------- helpers

    private Panchayat panchayat(String block, String name) {
        Panchayat p = new Panchayat();
        p.setBlockName(block);
        p.setName(name);
        return panchayats.saveAndFlush(p);
    }

    private PanchayatOfficial official(Panchayat p, String username) {
        PanchayatOfficial o = new PanchayatOfficial();
        o.setFullName("Official " + username);
        o.setUsername(username);
        o.setPanchayat(p);
        return officials.saveAndFlush(o);
    }

    private Farmer farmer(Panchayat p, String code, String name) {
        Farmer f = new Farmer();
        f.setFarmerCode(code);
        f.setFullName(name);
        f.setPanchayat(p);
        return farmers.saveAndFlush(f);
    }

    private Farm farm(Farmer f, String block) {
        Farm farm = new Farm();
        farm.setBlockName(block);
        // Maintain both sides: the inverse collections drive JPA cascades.
        f.addFarm(farm);
        return farms.saveAndFlush(farm);
    }

    private CropPlanting planting(Farm farm, String crop, String season, int year) {
        CropPlanting c = new CropPlanting();
        c.setCropName(crop);
        c.setSeason(season);
        c.setCropYear(year);
        // Maintain both sides: the inverse collections drive JPA cascades.
        farm.addCropPlanting(c);
        return plantings.saveAndFlush(c);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void panchayatCreation() {
        Panchayat p = panchayat("Sunam", "Suler Gherat");
        assertTrue(panchayats.findByBlockNameAndName("Sunam", "Suler Gherat").isPresent());
        assertEquals("Sangrur", p.getDistrictName());
        assertEquals("Punjab", p.getStateName());
    }

    @Test
    void officialLinkedToPanchayat() {
        Panchayat p = panchayat("Sunam", "Kularan");
        PanchayatOfficial o = official(p, "sunam.op1");
        assertTrue(officials.findByUsername("sunam.op1").isPresent());
        assertEquals(p.getId(), o.getPanchayat().getId());
        assertEquals(1, officials.findByPanchayatId(p.getId()).size());
    }

    @Test
    void farmerLinkedToPanchayat() {
        Panchayat p = panchayat("Sunam", "Chhajli");
        Farmer f = farmer(p, "SAN-SUN-000001", "Ramesh Kumar");
        assertTrue(farmers.findByFarmerCode("SAN-SUN-000001").isPresent());
        assertEquals(1, farmers.findByPanchayatId(p.getId()).size());
        // Minimum registration: phone/village/notes stay null.
        assertNull(f.getPhone());
        assertNull(f.getVillage());
    }

    @Test
    void farmerRegistrarDoesNotConferOwnership() {
        Panchayat p = panchayat("Dhuri", "Benra");
        PanchayatOfficial o = official(p, "dhuri.op1");
        Farmer f = farmer(p, "SAN-DHU-000001", "Ajeet Singh");
        f.setRegisteredByOfficial(o);
        farmers.saveAndFlush(f);
        // Unlink, then delete the official: the farmer must survive.
        f.setRegisteredByOfficial(null);
        farmers.saveAndFlush(f);
        officials.delete(o);
        officials.flush();
        assertTrue(farmers.findByFarmerCode("SAN-DHU-000001").isPresent());
    }

    @Test
    void farmerCanHaveMultipleFarms() {
        Panchayat p = panchayat("Sunam", "Dirba");
        Farmer f = farmer(p, "SAN-SUN-000002", "Multi Farm");
        farm(f, "Sunam");
        Farm second = farm(f, "Sunam");
        second.setPlotLabel("Farm 2");
        farms.saveAndFlush(second);
        assertEquals(2, farms.findByFarmerId(f.getId()).size());
    }

    @Test
    void cropHistoryPreservedAndCurrentResolvedByQuery() {
        Panchayat p = panchayat("Sunam", "Cheema");
        Farm farm = farm(farmer(p, "SAN-SUN-000003", "History Farmer"), "Sunam");
        planting(farm, "Paddy", "KHARIF", 2025);
        planting(farm, "Wheat", "RABI", 2026);
        planting(farm, "Paddy", "KHARIF", 2026);

        List<CropPlanting> history =
                plantings.findByFarmIdOrderByCropYearDescCreatedAtDescIdDesc(farm.getId());
        assertEquals(3, history.size());

        CropPlanting current = plantings
                .findFirstByFarmIdOrderByCropYearDescCreatedAtDescIdDesc(farm.getId()).orElseThrow();
        assertEquals("Paddy", current.getCropName());
        assertEquals(2026, current.getCropYear());
        assertEquals("KHARIF", current.getSeason());
    }

    @Test
    void uniqueUsernameRejected() {
        Panchayat p = panchayat("Lehra", "Gaga");
        official(p, "dup.user");
        PanchayatOfficial clash = new PanchayatOfficial();
        clash.setFullName("Someone Else");
        clash.setUsername("dup.user");
        clash.setPanchayat(p);
        assertThrows(DataIntegrityViolationException.class,
                () -> officials.saveAndFlush(clash));
    }

    @Test
    void uniqueFarmerCodeRejected() {
        Panchayat p = panchayat("Moonak", "Kakra");
        farmer(p, "SAN-MOO-000001", "First");
        Farmer clash = new Farmer();
        clash.setFarmerCode("SAN-MOO-000001");
        clash.setFullName("Second");
        clash.setPanchayat(p);
        assertThrows(DataIntegrityViolationException.class,
                () -> farmers.saveAndFlush(clash));
    }

    @Test
    void duplicatePanchayatInSameBlockRejected() {
        panchayat("Sangrur", "Ubhawal");
        Panchayat clash = new Panchayat();
        clash.setBlockName("Sangrur");
        clash.setName("Ubhawal");
        assertThrows(DataIntegrityViolationException.class,
                () -> panchayats.saveAndFlush(clash));
    }

    @Test
    void samePanchayatNameInDifferentBlocksAllowed() {
        panchayat("Sangrur", "Ubhawal");
        Panchayat other = new Panchayat();
        other.setBlockName("Sunam");
        other.setName("Ubhawal");
        assertDoesNotThrow(() -> panchayats.saveAndFlush(other));
    }

    @Test
    void duplicateCropPlantingRejected() {
        Panchayat p = panchayat("Sunam", "Mehlan");
        Farm farm = farm(farmer(p, "SAN-SUN-000004", "Dup Crop"), "Sunam");
        planting(farm, "Paddy", "KHARIF", 2026);
        CropPlanting clash = new CropPlanting();
        clash.setFarm(farm);
        clash.setCropName("Paddy");
        clash.setSeason("KHARIF");
        clash.setCropYear(2026);
        assertThrows(DataIntegrityViolationException.class,
                () -> plantings.saveAndFlush(clash));
    }

    @Test
    void multipleSeasonsAndYearsAllowed() {
        Panchayat p = panchayat("Sunam", "Togawal");
        Farm farm = farm(farmer(p, "SAN-SUN-000005", "Multi Season"), "Sunam");
        planting(farm, "Paddy", "KHARIF", 2025);
        planting(farm, "Wheat", "RABI", 2026);
        planting(farm, "Cotton", "KHARIF", 2026);
        assertEquals(3, plantings.findByFarmId(farm.getId()).size());
    }

    @Test
    void nonPositiveAreaRejected() {
        Panchayat p = panchayat("Sunam", "Namol");
        Farmer f = farmer(p, "SAN-SUN-000006", "Area Farmer");
        Farm zero = new Farm();
        zero.setFarmer(f);
        zero.setBlockName("Sunam");
        zero.setAreaAcres(0.0);
        assertThrows(ConstraintViolationException.class, () -> farms.saveAndFlush(zero));
    }

    @Test
    void latitudeRangeEnforced() {
        Panchayat p = panchayat("Sunam", "Jakhepal");
        Farmer f = farmer(p, "SAN-SUN-000007", "Lat Farmer");
        Farm bad = new Farm();
        bad.setFarmer(f);
        bad.setBlockName("Sunam");
        bad.setLatitude(91.0);
        bad.setLongitude(75.8);
        assertThrows(ConstraintViolationException.class, () -> farms.saveAndFlush(bad));
    }

    @Test
    void longitudeRangeEnforced() {
        Panchayat p = panchayat("Sunam", "Janal");
        Farmer f = farmer(p, "SAN-SUN-000008", "Lon Farmer");
        Farm bad = new Farm();
        bad.setFarmer(f);
        bad.setBlockName("Sunam");
        bad.setLatitude(30.0);
        bad.setLongitude(181.0);
        assertThrows(ConstraintViolationException.class, () -> farms.saveAndFlush(bad));
    }

    @Test
    void latitudeAndLongitudeMustAppearTogether() {
        Panchayat p = panchayat("Sunam", "Khanal Kalan");
        Farmer f = farmer(p, "SAN-SUN-000009", "Pair Farmer");
        Farm bad = new Farm();
        bad.setFarmer(f);
        bad.setBlockName("Sunam");
        bad.setLatitude(30.08);
        assertThrows(ConstraintViolationException.class, () -> farms.saveAndFlush(bad));
    }

    @Test
    void validCoordinatesWithMethodAccepted() {
        Panchayat p = panchayat("Sunam", "Khanal Khurd");
        Farmer f = farmer(p, "SAN-SUN-000010", "Gps Farmer");
        Farm ok = new Farm();
        ok.setFarmer(f);
        ok.setBlockName("Sunam");
        ok.setLatitude(30.08087);
        ok.setLongitude(75.86092);
        ok.setLocationMethod("GPS_CAPTURE");
        assertDoesNotThrow(() -> farms.saveAndFlush(ok));
    }

    @Test
    void harvestBeforeSowingRejected() {
        Panchayat p = panchayat("Sunam", "Bigarwal");
        Farm farm = farm(farmer(p, "SAN-SUN-000011", "Date Farmer"), "Sunam");
        CropPlanting bad = new CropPlanting();
        bad.setFarm(farm);
        bad.setCropName("Wheat");
        bad.setSeason("RABI");
        bad.setCropYear(2026);
        bad.setSowingDate(LocalDate.parse("2026-11-15"));
        bad.setHarvestDate(LocalDate.parse("2026-04-01"));
        assertThrows(ConstraintViolationException.class, () -> plantings.saveAndFlush(bad));
    }

    @Test
    void deletingFarmerCascadesToFarms() {
        Panchayat p = panchayat("Dhuri", "Maanwala");
        Farmer f = farmer(p, "SAN-DHU-000002", "Cascade Farmer");
        farm(f, "Dhuri");
        farmers.delete(f);
        farmers.flush();
        assertTrue(farms.findByFarmerId(f.getId()).isEmpty());
    }

    @Test
    void deletingFarmCascadesToPlantings() {
        Panchayat p = panchayat("Dhuri", "Rangian");
        Farmer f = farmer(p, "SAN-DHU-000003", "Crop Cascade");
        Farm farm = farm(f, "Dhuri");
        planting(farm, "Paddy", "KHARIF", 2026);
        Long farmId = farm.getId();
        // Remove through the aggregate root: with orphanRemoval, a direct
        // remove() of a child that is still referenced by the managed
        // parent collection is reconciled as reachable and silently kept.
        f.removeFarm(farm);
        farmers.saveAndFlush(f);
        assertTrue(farms.findById(farmId).isEmpty());
        assertTrue(plantings.findByFarmId(farmId).isEmpty());
    }

    @Test
    void deletingPanchayatWithFarmersIsRestricted() {
        Panchayat p = panchayat("Malerkotla", "Sandaur");
        farmer(p, "SAN-MAL-000001", "Blocked Delete");
        assertThrows(DataIntegrityViolationException.class,
                () -> {
                    panchayats.delete(p);
                    panchayats.flush();
                });
    }

    @Test
    void farmerCodesAreFormattedAndUnique() {
        FarmerCodeService codes = new FarmerCodeService(farmers);
        Pattern shape = Pattern.compile("SAN-[A-Z]{3}-\\d{6}");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = codes.generateUniqueCode("Sunam");
            assertTrue(shape.matcher(code).matches(), "unexpected code shape: " + code);
            assertTrue(seen.add(code), "duplicate code generated: " + code);
        }
        assertEquals("SAN-SUN-000123".substring(0, 8),
                codes.generateUniqueCode("Sunam").substring(0, 8));
    }

    @Test
    void panchayatSeedIsIdempotent() {
        PanchayatSeedService seed = new PanchayatSeedService(panchayats, new DistrictDataService());
        int total = new DistrictDataService().getTotalPanchayats();
        assertEquals(total, seed.seed());
        assertEquals(total, panchayats.count());
        assertEquals(0, seed.seed());
        assertEquals(total, panchayats.count());
    }

    @Test
    void existingFeedbackStillWorks() {
        Feedback fb = new Feedback();
        fb.setBlock("Sunam");
        fb.setFarmerName("Ramesh Kumar");
        fb.setCropStage("tillering");
        fb.setAdvisoryHelpful(true);
        fb.setComments("Useful advisory");
        Feedback saved = feedbacks.saveAndFlush(fb);
        assertNotNull(saved.getId());
        assertEquals(1, feedbacks.count());
    }

    @Test
    void farmersSearchableByPanchayatAndName() {
        Panchayat p = panchayat("Sunam", "Mauran");
        farmer(p, "SAN-SUN-000012", "Ramesh Kumar");
        farmer(p, "SAN-SUN-000013", "Suresh Singh");
        List<Farmer> hits = farmers.findByPanchayatIdAndFullNameContainingIgnoreCase(
                p.getId(), "ramesh");
        assertEquals(1, hits.size());
        assertEquals("Ramesh Kumar", hits.get(0).getFullName());
    }

    // ------------------------------------------------------- assignment tests

    private FarmerAssignmentService assignments() {
        return new FarmerAssignmentService(farmers, officials);
    }

    @Test
    void oneOfficialHasManyAssignedFarmers() {
        Panchayat p = panchayat("Sunam", "Dhandiwal");
        PanchayatOfficial o = official(p, "assign.op1");
        Farmer f1 = farmer(p, "SAN-ASN-000001", "Farmer One");
        Farmer f2 = farmer(p, "SAN-ASN-000002", "Farmer Two");
        Farmer f3 = farmer(p, "SAN-ASN-000003", "Farmer Three");
        FarmerAssignmentService service = assignments();
        service.assignOfficial(f1.getId(), o.getId());
        service.assignOfficial(f2.getId(), o.getId());
        service.assignOfficial(f3.getId(), o.getId());
        farmers.flush();

        List<Farmer> assigned = farmers.findByAssignedOfficialId(o.getId());
        assertEquals(3, assigned.size());
        assertEquals(3, service.assignedFarmers(o.getId()).size());
    }

    @Test
    void inverseAssignedFarmersViewResolves() {
        Panchayat p = panchayat("Sunam", "Shahpur Kaler");
        PanchayatOfficial o = official(p, "assign.opView");
        Farmer f1 = farmer(p, "SAN-ASN-000011", "View Farmer One");
        Farmer f2 = farmer(p, "SAN-ASN-000012", "View Farmer Two");
        FarmerAssignmentService service = assignments();
        service.assignOfficial(f1.getId(), o.getId());
        service.assignOfficial(f2.getId(), o.getId());
        farmers.flush();

        // Fresh session state: the inverse @OneToMany must resolve the same
        // two farmers the FK query returns.
        tem.clear();
        PanchayatOfficial fresh =
                officials.findById(o.getId()).orElseThrow();
        assertEquals(2, fresh.getAssignedFarmers().size());
        assertEquals(2, farmers.findByAssignedOfficialId(o.getId()).size());
    }

    @Test
    void farmerHasOneAssignedOfficial() {
        Panchayat p = panchayat("Sunam", "Kauhar Singh Wala");
        PanchayatOfficial o = official(p, "assign.op2");
        Farmer f = farmer(p, "SAN-ASN-000004", "Single Assign");
        assignments().assignOfficial(f.getId(), o.getId());
        farmers.flush();

        Farmer reloaded = farmers.findByFarmerCode("SAN-ASN-000004").orElseThrow();
        assertEquals(o.getId(), reloaded.getAssignedOfficial().getId());
    }

    @Test
    void farmerReassignedBetweenOfficialsOfSamePanchayat() {
        Panchayat p = panchayat("Lehra", "Kotra Amru");
        PanchayatOfficial a = official(p, "assign.opA");
        PanchayatOfficial b = official(p, "assign.opB");
        Farmer f = farmer(p, "SAN-ASN-000005", "Reassign Farmer");
        FarmerAssignmentService service = assignments();

        service.assignOfficial(f.getId(), a.getId());
        assertEquals(1, farmers.findByAssignedOfficialId(a.getId()).size());

        // Reassignment updates the same row — no duplicate farmer.
        long rowsBefore = farmers.count();
        service.assignOfficial(f.getId(), b.getId());
        farmers.flush();
        assertEquals(rowsBefore, farmers.count());

        assertTrue(farmers.findByAssignedOfficialId(a.getId()).isEmpty());
        List<Farmer> nowWithB = farmers.findByAssignedOfficialId(b.getId());
        assertEquals(1, nowWithB.size());
        assertEquals(f.getId(), nowWithB.get(0).getId());
    }

    @Test
    void crossPanchayatAssignmentRejected() {
        Panchayat ubhawalSangrur = panchayat("Sangrur", "Ubhawal");
        Panchayat ubhawalSunam = panchayat("Sunam", "Ubhawal");
        PanchayatOfficial outsider = official(ubhawalSunam, "assign.outsider");
        Farmer f = farmer(ubhawalSangrur, "SAN-ASN-000006", "Local Farmer");

        assertThrows(IllegalArgumentException.class, () ->
                assignments().assignOfficial(f.getId(), outsider.getId()));
        farmers.flush();

        assertNull(farmers.findByFarmerCode("SAN-ASN-000006")
                .orElseThrow().getAssignedOfficial());
        assertTrue(farmers.findByAssignedOfficialId(outsider.getId()).isEmpty());
    }

    @Test
    void assignmentToUnknownIdsRejected() {
        Panchayat p = panchayat("Moonak", "Dehla");
        PanchayatOfficial o = official(p, "assign.op3");
        Farmer f = farmer(p, "SAN-ASN-000007", "Known Farmer");
        FarmerAssignmentService service = assignments();
        assertThrows(IllegalArgumentException.class, () ->
                service.assignOfficial(999_999L, o.getId()));
        assertThrows(IllegalArgumentException.class, () ->
                service.assignOfficial(f.getId(), 999_999L));
    }

    @Test
    void deactivatingOfficialKeepsAssignedFarmers() {
        Panchayat p = panchayat("Dhuri", "Pakhoke");
        PanchayatOfficial o = official(p, "assign.op4");
        Farmer f = farmer(p, "SAN-ASN-000008", "Steady Farmer");
        assignments().assignOfficial(f.getId(), o.getId());

        o.setActive(false);
        officials.saveAndFlush(o);

        assertEquals(o.getId(), farmers.findByFarmerCode("SAN-ASN-000008")
                .orElseThrow().getAssignedOfficial().getId());
        assertEquals(1, farmers.findByAssignedOfficialId(o.getId()).size());
    }

    @Test
    void unassigningThenDeletingOfficialKeepsFarmers() {
        Panchayat p = panchayat("Dhuri", "Rajomajra");
        PanchayatOfficial o = official(p, "assign.op5");
        Farmer f = farmer(p, "SAN-ASN-000009", "Surviving Farmer");
        FarmerAssignmentService service = assignments();
        service.assignOfficial(f.getId(), o.getId());
        service.unassignOfficial(f.getId());
        farmers.flush();

        officials.delete(o);
        officials.flush();

        assertTrue(farmers.findByFarmerCode("SAN-ASN-000009").isPresent());
    }

    @Test
    void registrationAuditLinkSurvivesAssignmentChange() {
        Panchayat p = panchayat("Malerkotla", "Himmatpura");
        PanchayatOfficial registrar = official(p, "assign.registrar");
        PanchayatOfficial monitor = official(p, "assign.monitor");
        Farmer f = farmer(p, "SAN-ASN-000010", "Audited Farmer");
        f.setRegisteredByOfficial(registrar);
        farmers.saveAndFlush(f);

        assignments().assignOfficial(f.getId(), monitor.getId());
        farmers.flush();

        Farmer reloaded = farmers.findByFarmerCode("SAN-ASN-000010").orElseThrow();
        // Audit (who registered) and responsibility (who monitors) differ.
        assertEquals(registrar.getId(), reloaded.getRegisteredByOfficial().getId());
        assertEquals(monitor.getId(), reloaded.getAssignedOfficial().getId());
    }
}
