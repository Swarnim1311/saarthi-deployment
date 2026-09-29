package com.saarthi.service;

import com.saarthi.model.Panchayat;
import com.saarthi.repository.PanchayatRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent seed of the six Saarthi blocks' Panchayats from
 * {@link DistrictDataService#BLOCK_PANCHAYATS} (authoritative source, left
 * unchanged). Re-running inserts nothing: each row is looked up by the
 * scoped key {@code (block_name, name)} first.
 *
 * <p>Bhuvan block ids come from {@code forecast/blocks.json};
 * state/district LGD codes from the geography registry defaults
 * (Punjab {@code 3}, Sangrur {@code 43}).
 */
@Service
public class PanchayatSeedService implements ApplicationRunner {

    /** Block name -&gt; bhuvan id, mirroring {@code forecast/blocks.json}. */
    static final Map<String, String> BHUVAN_BLOCK_IDS = new LinkedHashMap<>();

    static {
        BHUVAN_BLOCK_IDS.put("Dhuri", "bhuvan_b_270");
        BHUVAN_BLOCK_IDS.put("Lehra", "bhuvan_b_273");
        BHUVAN_BLOCK_IDS.put("Malerkotla", "bhuvan_b_269");
        BHUVAN_BLOCK_IDS.put("Moonak", "bhuvan_b_274");
        BHUVAN_BLOCK_IDS.put("Sangrur", "bhuvan_b_271");
        BHUVAN_BLOCK_IDS.put("Sunam", "bhuvan_b_272");
    }

    private final PanchayatRepository panchayats;
    private final DistrictDataService districts;

    public PanchayatSeedService(PanchayatRepository panchayats, DistrictDataService districts) {
        this.panchayats = panchayats;
        this.districts = districts;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * @return number of rows inserted (0 when already seeded).
     */
    @Transactional
    public int seed() {
        int inserted = 0;
        for (Map.Entry<String, java.util.List<String>> entry
                : DistrictDataService.BLOCK_PANCHAYATS.entrySet()) {
            String block = entry.getKey();
            for (String name : entry.getValue()) {
                if (panchayats.findByBlockNameAndName(block, name).isPresent()) {
                    continue;
                }
                Panchayat p = new Panchayat();
                p.setName(name);
                p.setBlockName(block);
                p.setDistrictName("Sangrur");
                p.setStateName("Punjab");
                p.setBhuvanBlockId(BHUVAN_BLOCK_IDS.get(block));
                p.setStateCode("3");
                p.setDistrictCode("43");
                panchayats.save(p);
                inserted++;
            }
        }
        return inserted;
    }
}
