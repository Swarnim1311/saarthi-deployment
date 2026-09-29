package com.saarthi.service;

import com.saarthi.dto.FarmerRegistrationRequest;
import com.saarthi.model.Farmer;
import com.saarthi.model.Panchayat;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.repository.FarmerRepository;
import com.saarthi.repository.PanchayatOfficialRepository;
import com.saarthi.repository.PanchayatRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class FarmerService {

    private final FarmerRepository farmerRepository;
    private final PanchayatRepository panchayatRepository;
    private final PanchayatOfficialRepository officialRepository;

    public FarmerService(
            FarmerRepository farmerRepository,
            PanchayatRepository panchayatRepository,
            PanchayatOfficialRepository officialRepository) {

        this.farmerRepository = farmerRepository;
        this.panchayatRepository = panchayatRepository;
        this.officialRepository = officialRepository;
    }

    @Transactional
    public Farmer registerFarmer(FarmerRegistrationRequest request) {

        Panchayat panchayat = panchayatRepository.findById(request.getPanchayatId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Panchayat not found: " + request.getPanchayatId()));

        PanchayatOfficial official = null;

        if (request.getRegisteredByOfficialId() != null) {

            official = officialRepository.findById(
                            request.getRegisteredByOfficialId())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Panchayat official not found: "
                                            + request.getRegisteredByOfficialId()));

            if (!official.isActive()) {
                throw new IllegalArgumentException(
                        "Panchayat official is inactive");
            }

            if (!official.getPanchayat().getId().equals(panchayat.getId())) {
                throw new IllegalArgumentException(
                        "Official does not belong to the selected Panchayat");
            }
        }

        Farmer farmer = new Farmer();

        farmer.setFullName(request.getFullName().trim());
        farmer.setPhone(normalizeOptional(request.getPhone()));
        farmer.setVillage(normalizeOptional(request.getVillage()));
        farmer.setPanchayat(panchayat);
        farmer.setRegisteredByOfficial(official);
        farmer.setNotes(normalizeOptional(request.getNotes()));

        /*
         * farmer_code is NOT NULL in PostgreSQL.
         *
         * We therefore initially set a temporary unique value.
         * PostgreSQL generates the farmer ID during save().
         * We then replace it with the final human-readable code.
         */
        farmer.setFarmerCode("TEMP-" + System.nanoTime());

        farmer = farmerRepository.saveAndFlush(farmer);

        String farmerCode = generateFarmerCode(panchayat, farmer.getId());

        farmer.setFarmerCode(farmerCode);

        return farmerRepository.save(farmer);
    }

    private String generateFarmerCode(Panchayat panchayat, Long farmerId) {

        String blockCode = panchayat.getBlockName()
                .replaceAll("[^A-Za-z]", "")
                .toUpperCase();

        blockCode = blockCode.substring(
                0,
                Math.min(3, blockCode.length())
        );

        return String.format(
                "SAN-%s-%06d",
                blockCode,
                farmerId
        );
    }

    private String normalizeOptional(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}