package com.saarthi.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Static district reference data: the six legacy Bhuvan blocks and their
 * panchayat lists (used for the farmer form dropdown).
 *
 * This service holds NO rainfall forecasts, NO moisture numbers and NO risk
 * scores — live values come from {@link RealForecastService}.
 */
@Service
public class DistrictDataService {

    public static final List<String> BLOCKS = Collections.unmodifiableList(Arrays.asList(
            "Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"
    ));

    public static final Map<String, List<String>> BLOCK_PANCHAYATS = new LinkedHashMap<>();

    static {
        BLOCK_PANCHAYATS.put("Dhuri", Arrays.asList(
                "Rangian", "Maanwala", "Benra", "Kalerian", "Babbanpur", "Jahangir",
                "Ranike", "Mullowal", "Mimsa", "Bardwal", "Pakhoke", "Rajomajra",
                "Daulatpur", "Bhulran", "Bhalwan Dhuri", "Bugra"
        ));
        BLOCK_PANCHAYATS.put("Lehra", Arrays.asList(
                "Lehal Kalan", "Kotra Amru", "Chhajli Khurd", "Sangha", "Alampur",
                "Gaga", "Dhadrian", "Bakhoran Kalan", "Sekhuwas", "Bhutal Kalan",
                "Chotian", "Khokhar", "Anderana", "Balran Lehra"
        ));
        BLOCK_PANCHAYATS.put("Malerkotla", Arrays.asList(
                "Ahmedgarh Rural", "Kup Kalan", "Himmatpura",
                "Jamalpura", "Sandaur", "Bhadas", "Mithewal", "Maholi Kalan",
                "Nathuwala", "Rohira", "Chaunda", "Balyal"
        ));
        BLOCK_PANCHAYATS.put("Moonak", Arrays.asList(
                "Moonak Rural", "Ghamoor Ghat", "Makror Sahib", "Kakra", "Mandvi",
                "Hamirgarh", "Surjan Bhaini", "Lehal Khurd", "Balran", "Dehla",
                "Bhundar Bhaini", "Ramnagar Sibian", "Banawali", "Bushehra"
        ));
        BLOCK_PANCHAYATS.put("Sangrur", Arrays.asList(
                "Bhalwan", "Mangwal", "Ubhawal", "Kanganwal", "Badrukhan", "Ladda",
                "Akoi Sahib", "Ghabdan", "Duggan", "Khurana",
                "Fatehgarh Chhanna", "Soian", "Uppli", "Gharachon", "Bahadurpur"
        ));
        BLOCK_PANCHAYATS.put("Sunam", Arrays.asList(
                "Suler Gherat", "Kularan", "Chhajli", "Dirba", "Cheema", "Mehlan", "Ubhawal",
                "Togawal", "Shahpur Kaler", "Bigarwal", "Dhandiwal", "Mauran", "Namol",
                "Jakhepal", "Janal", "Khanal Kalan", "Khanal Khurd", "Kauhar Singh Wala"
        ));
    }

    public List<String> getBlocks() { return BLOCKS; }

    public boolean isKnownBlock(String block) {
        return block != null && BLOCK_PANCHAYATS.containsKey(block);
    }

    /**
     * Panchayat list for a known block. Returns {@code null} for unknown blocks —
     * callers must return HTTP 404 (unknown_block), never silently substitute Sunam.
     */
    public List<String> getPanchayats(String block) {
        return BLOCK_PANCHAYATS.get(block);
    }

    public int getTotalPanchayats() {
        return BLOCK_PANCHAYATS.values().stream().mapToInt(List::size).sum();
    }
}
