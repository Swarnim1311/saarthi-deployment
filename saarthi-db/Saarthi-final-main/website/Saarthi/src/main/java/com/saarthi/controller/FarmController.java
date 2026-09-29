package com.saarthi.controller;

import com.saarthi.dto.FarmRegistrationRequest;
import com.saarthi.model.Farm;
import com.saarthi.service.FarmService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farmers/{farmerId}/farms")
public class FarmController {

    private final FarmService farmService;

    public FarmController(FarmService farmService) {
        this.farmService = farmService;
    }

    @PostMapping
    public ResponseEntity<?> registerFarm(
            @PathVariable Long farmerId,
            @Valid @RequestBody FarmRegistrationRequest request) {

        try {

            Farm farm = farmService.registerFarm(
                    farmerId,
                    request
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of(
                            "id", farm.getId(),
                            "farmerId", farm.getFarmer().getId(),
                            "plotLabel", farm.getPlotLabel() == null
                                    ? ""
                                    : farm.getPlotLabel(),
                            "areaAcres", farm.getAreaAcres() == null
                                    ? 0
                                    : farm.getAreaAcres(),
                            "village", farm.getVillage() == null
                                    ? ""
                                    : farm.getVillage(),
                            "blockName", farm.getBlockName(),
                            "panchayatName", farm.getPanchayatName(),
                            "soilTexture", farm.getSoilTexture() == null
                                    ? ""
                                    : farm.getSoilTexture(),
                            "irrigationSource",
                            farm.getIrrigationSource() == null
                                    ? ""
                                    : farm.getIrrigationSource()
                    ));

        } catch (IllegalArgumentException e) {

            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error", e.getMessage()
                    ));
        }
    }
}