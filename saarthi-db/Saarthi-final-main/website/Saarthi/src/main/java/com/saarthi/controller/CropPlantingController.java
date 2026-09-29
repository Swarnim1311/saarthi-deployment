package com.saarthi.controller;

import com.saarthi.dto.CropPlantingRegistrationRequest;
import com.saarthi.model.CropPlanting;
import com.saarthi.service.CropPlantingService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farms/{farmId}/crops")
public class CropPlantingController {

    private final CropPlantingService cropPlantingService;

    public CropPlantingController(CropPlantingService cropPlantingService) {
        this.cropPlantingService = cropPlantingService;
    }

    @PostMapping
    public ResponseEntity<?> registerCrop(
            @PathVariable Long farmId,
            @Valid @RequestBody CropPlantingRegistrationRequest request) {

        try {
            CropPlanting crop = cropPlantingService.registerCrop(farmId, request);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "id", crop.getId(),
                            "farmId", crop.getFarm().getId(),
                            "cropName", crop.getCropName(),
                            "variety", crop.getVariety() == null ? "" : crop.getVariety(),
                            "season", crop.getSeason(),
                            "cropYear", crop.getCropYear(),
                            "sowingDate", crop.getSowingDate() == null
                                    ? "" : crop.getSowingDate().toString(),
                            "harvestDate", crop.getHarvestDate() == null
                                    ? "" : crop.getHarvestDate().toString()
                    ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}