package com.saarthi.service;

import com.saarthi.repository.FarmerRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;

/**
 * Generates globally unique farmer codes such as {@code SAN-SUN-000123}.
 *
 * <p>Concurrency safety comes from the database {@code UNIQUE(farmer_code)}
 * constraint as the final arbiter: codes are random within a large space,
 * pre-checked with {@code existsByFarmerCode}, and any residual race fails
 * on the unique constraint (caller retries registration) instead of
 * silently duplicating. This avoids a fragile {@code count(*)} approach
 * and needs no database-specific sequence, so it works identically on
 * PostgreSQL and H2.
 */
@Service
public class FarmerCodeService {

    static final int MAX_ATTEMPTS = 20;

    private final FarmerRepository farmers;
    private final SecureRandom random = new SecureRandom();

    public FarmerCodeService(FarmerRepository farmers) {
        this.farmers = farmers;
    }

    /**
     * @param blockName e.g. {@code Sunam}; first 3 letters form the code
     *                  segment ({@code SUN}). Null/blank yields {@code GEN}.
     */
    public String generateUniqueCode(String blockName) {
        String segment = blockSegment(blockName);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = String.format("SAN-%s-%06d", segment, random.nextInt(1_000_000));
            if (!farmers.existsByFarmerCode(code)) {
                return code;
            }
        }
        // Vanishingly unlikely fallback: timestamp suffix guarantees progress.
        return String.format("SAN-%s-%d", segment, System.currentTimeMillis() % 1_000_000L);
    }

    static String blockSegment(String blockName) {
        if (blockName == null) {
            return "GEN";
        }
        String letters = blockName.replaceAll("[^A-Za-z]", "").toUpperCase();
        if (letters.isEmpty()) {
            return "GEN";
        }
        return (letters + "XXX").substring(0, 3);
    }
}
