package com.saarthi.service;

import com.saarthi.dto.FarmRegistrationRequest;
import com.saarthi.model.Farm;
import com.saarthi.model.Farmer;
import com.saarthi.model.Panchayat;
import com.saarthi.repository.FarmRepository;
import com.saarthi.repository.FarmerRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class FarmService {

    private final FarmRepository farmRepository;
    private final FarmerRepository farmerRepository;

    public FarmService(
            FarmRepository farmRepository,
            FarmerRepository farmerRepository) {

        this.farmRepository = farmRepository;
        this.farmerRepository = farmerRepository;
    }

    @Transactional
    public Farm registerFarm(
            Long farmerId,
            FarmRegistrationRequest request) {

        Farmer farmer = farmerRepository.findById(farmerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Farmer not found: " + farmerId));

        Panchayat panchayat = farmer.getPanchayat();

        if (panchayat == null) {
            throw new IllegalArgumentException(
                    "Farmer is not associated with a Panchayat");
        }

        Farm farm = new Farm();

        farm.setFarmer(farmer);

        farm.setPlotLabel(
                normalizeOptional(request.getPlotLabel()));

        farm.setAreaAcres(request.getAreaAcres());

        String village = normalizeOptional(request.getVillage());

        if (village == null) {
            village = farmer.getVillage();
        }

        farm.setVillage(village);

        /*
         * These values are derived from the farmer's Panchayat.
         * They are NOT supplied by the client.
         */
        farm.setBlockName(panchayat.getBlockName());
        farm.setPanchayatName(panchayat.getName());

        /*
         * Latitude and longitude intentionally remain NULL.
         * Saarthi does not collect farm coordinates in this flow.
         */

        farm.setSoilTexture(
                normalizeOptional(request.getSoilTexture()));

        farm.setIrrigationSource(
                normalizeOptional(request.getIrrigationSource()));

        return farmRepository.save(farm);
    }

    private String normalizeOptional(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}