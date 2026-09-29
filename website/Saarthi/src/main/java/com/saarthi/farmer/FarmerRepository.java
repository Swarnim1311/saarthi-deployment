package com.saarthi.farmer;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory farmer store — the simplest architecture consistent with the
 * current Spring project (no new database, no file writes, no standalone
 * server).
 *
 * <p>Seeded from the teammate's starting data
 * ({@code SAARTHI-VOICE/backend/data/farmers.json}). Lookup is
 * case-insensitive on the trimmed id, mirroring {@code getFarmerById}.
 */
@Repository
public class FarmerRepository {

    private final Map<String, Farmer> store = new ConcurrentHashMap<>();

    public FarmerRepository() {
        seed(new Farmer("F001", "Vaishnavi", "Rice", "Amritsar", "Amritsar",
                "Punjab", "2 acres", "2026-09-09", "2026-09-11T07:41:19.116Z"));
        seed(new Farmer("F002", "Ramesh Kumar", "Wheat", "Vijayawada", "NTR",
                "Andhra Pradesh", "3 acres", "2024-11-10", null));
        seed(new Farmer("F003", "Sukhwinder Kaur", "Cotton", "Bathinda", "Bathinda",
                "Punjab", "4 acres", "2024-05-10", "2026-09-11T07:28:50.947Z"));
    }

    /** All profiles, in id order. Callers get copies, never live references. */
    public List<Farmer> findAll() {
        List<Farmer> all = new ArrayList<>(store.values());
        all.sort((a, b) -> String.valueOf(a.getFarmerId())
                .compareTo(String.valueOf(b.getFarmerId())));
        return all.stream().map(FarmerRepository::copy).toList();
    }

    /**
     * One profile by id (case-insensitive, trimmed), or {@code null}.
     * Only the requested record is ever returned — never the full list.
     */
    public Farmer findById(String farmerId) {
        String key = keyOf(farmerId);
        if (key == null) return null;
        Farmer found = store.get(key);
        return found == null ? null : copy(found);
    }

    /** Insert or update (matched case-insensitively), stamping {@code updatedAt}. */
    public Farmer save(Farmer farmer) {
        Farmer clean = copy(farmer);
        clean.setUpdatedAt(Instant.now().toString());
        store.put(keyOf(clean.getFarmerId()), clean);
        return copy(clean);
    }

    private void seed(Farmer farmer) {
        store.put(keyOf(farmer.getFarmerId()), farmer);
    }

    private static String keyOf(String farmerId) {
        if (farmerId == null) return null;
        String key = farmerId.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static Farmer copy(Farmer f) {
        return new Farmer(f.getFarmerId(), f.getFarmerName(), f.getCrop(),
                f.getCropLocation(), f.getDistrict(), f.getState(), f.getLandSize(),
                f.getSowingDate(), f.getUpdatedAt());
    }
}
