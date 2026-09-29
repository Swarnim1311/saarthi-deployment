package com.saarthi.service;

import com.saarthi.dto.CropPlantingRegistrationRequest;
import com.saarthi.model.CropPlanting;
import com.saarthi.model.Farm;
import com.saarthi.repository.CropPlantingRepository;
import com.saarthi.repository.FarmRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class CropPlantingService {

    private final CropPlantingRepository cropPlantingRepository;
    private final FarmRepository farmRepository;

    public CropPlantingService(
            CropPlantingRepository cropPlantingRepository,
            FarmRepository farmRepository) {
        this.cropPlantingRepository = cropPlantingRepository;
        this.farmRepository = farmRepository;
    }

    @Transactional
    public CropPlanting registerCrop(
            Long farmId,
            CropPlantingRegistrationRequest request) {

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Farm not found: " + farmId));

        CropPlanting crop = new CropPlanting();

        crop.setFarm(farm);
        crop.setCropName(normalizeRequired(request.getCropName()));
        crop.setVariety(normalizeOptional(request.getVariety()));
        crop.setSeason(request.getSeason());
        crop.setCropYear(request.getCropYear());
        crop.setSowingDate(request.getSowingDate());
        crop.setHarvestDate(request.getHarvestDate());

        return cropPlantingRepository.save(crop);
    }

    private String normalizeRequired(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("cropName is required");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}