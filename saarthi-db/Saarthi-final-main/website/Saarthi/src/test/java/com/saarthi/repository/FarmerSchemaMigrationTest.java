package com.saarthi.repository;

import com.saarthi.model.CropPlanting;
import com.saarthi.model.Farm;
import com.saarthi.model.Farmer;
import com.saarthi.model.Panchayat;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.service.DistrictDataService;
import com.saarthi.service.PanchayatSeedService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the REAL Flyway migrations ({@code V1__farmer_schema.sql} +
 * {@code V2__farmer_official_assignment.sql}) on H2 and then boots
 * Hibernate with {@code ddl-auto=validate}, proving the JPA entities match
 * the migrations. Also verifies the migrations' {@code ON DELETE} actions
 * (CASCADE / SET NULL / RESTRICT), which the Hibernate-generated slice
 * schema in {@code FarmerDatabaseTest} cannot cover.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class FarmerSchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;
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
    DistrictDataService districts;
    @Autowired
    PanchayatSeedService seed;

    @Test
    void flywayAppliedV1AndV2Successfully() {
        // NOTE: quoted lowercase table name — Flyway creates the history
        // table quoted-lowercase on H2 (unquoted folds to upper and misses),
        // and quoted-lowercase matches PostgreSQL's folding too. VERSION and
        // SUCCESS are read Java-side (VERSION is reserved in H2; key case
        // differs per dialect).
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"");
        for (String expected : List.of("1", "2")) {
            boolean applied = rows.stream().anyMatch(row -> {
                Object success = row.getOrDefault("SUCCESS", row.get("success"));
                Object version = row.getOrDefault("VERSION", row.get("version"));
                return Boolean.TRUE.equals(success) && expected.equals(String.valueOf(version));
            });
            assertTrue(applied, "V" + expected + " migration must be applied, rows: " + rows);
        }
    }

    @Test
    void v2AssignmentColumnConstraintAndIndexExist() {
        Integer columns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'FARMER' AND COLUMN_NAME = 'ASSIGNED_OFFICIAL_ID'",
                Integer.class);
        assertEquals(1, columns, "farmer.assigned_official_id must exist (V2)");
        Integer indexes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'FARMER' AND INDEX_NAME = 'IDX_FARMER_ASSIGNED_OFFICIAL'",
                Integer.class);
        assertEquals(1, indexes, "idx_farmer_assigned_official must exist (V2)");
    }

    @Test
    void allSixTablesExist() {
        for (String table : List.of("feedback", "panchayat", "panchayat_official",
                "farmer", "farm", "crop_planting")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                    Integer.class, table.toUpperCase());
            assertEquals(1, count, "missing table: " + table);
        }
    }

    @Test
    void seedPopulatesAllPanchayatsIdempotentlyInLiveContext() {
        // Sibling tests may add their own Panchayat rows, so assert the
        // idempotency property (re-seed inserts nothing, count is stable)
        // rather than an exact total.
        int total = districts.getTotalPanchayats();
        assertTrue(panchayats.count() >= total,
                "seeded panchayats must cover all " + total + " reference rows");
        long before = panchayats.count();
        assertEquals(0, seed.seed());
        assertEquals(before, panchayats.count());
    }

    @Test
    void deletingOfficialSetsRegistrarNull() {
        Panchayat p = panchayats.findByBlockNameAndName("Sunam", "Suler Gherat").orElseThrow();
        PanchayatOfficial o = new PanchayatOfficial();
        o.setFullName("Seed Official");
        o.setUsername("migration.op1");
        o.setPanchayat(p);
        o = officials.saveAndFlush(o);

        Farmer f = new Farmer();
        f.setFarmerCode("SAN-MIG-000001");
        f.setFullName("Migration Farmer");
        f.setPanchayat(p);
        f.setRegisteredByOfficial(o);
        f = farmers.saveAndFlush(f);
        Long farmerId = f.getId();

        officials.delete(o);
        officials.flush();

        Farmer reloaded = farmers.findById(farmerId).orElseThrow();
        assertNull(reloaded.getRegisteredByOfficial());
        assertEquals("Migration Farmer", reloaded.getFullName());
    }

    @Test
    void deletingOfficialClearsAssignmentWithoutDeletingFarmers() {
        Panchayat p = panchayats.findByBlockNameAndName("Sunam", "Kularan").orElseThrow();
        PanchayatOfficial o = new PanchayatOfficial();
        o.setFullName("Assignment Official");
        o.setUsername("migration.opAssign");
        o.setPanchayat(p);
        o = officials.saveAndFlush(o);

        Farmer f = new Farmer();
        f.setFarmerCode("SAN-MIG-000010");
        f.setFullName("Assigned Migration Farmer");
        f.setPanchayat(p);
        f.setAssignedOfficial(o);
        f = farmers.saveAndFlush(f);
        Long farmerId = f.getId();

        Farm farm = new Farm();
        farm.setFarmer(f);
        farm.setBlockName("Sunam");
        farm = farms.saveAndFlush(farm);

        // Direct delete: the V2 FK is ON DELETE SET NULL, so the farmer
        // (and their farm) must survive with a cleared assignment.
        officials.delete(o);
        officials.flush();

        Farmer reloaded = farmers.findById(farmerId).orElseThrow();
        assertNull(reloaded.getAssignedOfficial());
        assertEquals("Assigned Migration Farmer", reloaded.getFullName());
        assertEquals(1, farms.findByFarmerId(farmerId).size());
        assertTrue(farmers.findByAssignedOfficialId(o.getId()).isEmpty());
    }

    @Test
    void deletingFarmerCascadesThroughMigrationSchema() {
        Panchayat p = panchayats.findByBlockNameAndName("Dhuri", "Benra").orElseThrow();
        Farmer f = new Farmer();
        f.setFarmerCode("SAN-MIG-000002");
        f.setFullName("Cascade Farmer");
        f.setPanchayat(p);
        f = farmers.saveAndFlush(f);

        Farm farm = new Farm();
        farm.setFarmer(f);
        farm.setBlockName("Dhuri");
        farm = farms.saveAndFlush(farm);

        CropPlanting c = new CropPlanting();
        c.setFarm(farm);
        c.setCropName("Paddy");
        c.setSeason("KHARIF");
        c.setCropYear(2026);
        plantings.saveAndFlush(c);
        Long farmId = farm.getId();

        farmers.delete(f);
        farmers.flush();

        assertTrue(farms.findByFarmerId(f.getId()).isEmpty());
        assertTrue(plantings.findByFarmId(farmId).isEmpty());
    }

    @Test
    void deletingPanchayatWithFarmersIsRestrictedByMigration() {
        Panchayat p = new Panchayat();
        p.setBlockName("Moonak");
        p.setName("Migration Village");
        p = panchayats.saveAndFlush(p);

        Farmer f = new Farmer();
        f.setFarmerCode("SAN-MIG-000003");
        f.setFullName("Restrict Farmer");
        f.setPanchayat(p);
        farmers.saveAndFlush(f);

        Panchayat doomed = p;
        assertThrows(DataIntegrityViolationException.class, () -> {
            panchayats.delete(doomed);
            panchayats.flush();
        });
    }
}
