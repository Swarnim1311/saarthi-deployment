package com.saarthi.controller;

import com.saarthi.dto.FarmerRegistrationRequest;
import com.saarthi.model.Farmer;
import com.saarthi.service.FarmerService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farmers")
public class FarmerController {

    private final FarmerService farmerService;

    public FarmerController(FarmerService farmerService) {
        this.farmerService = farmerService;
    }

    @PostMapping
    public ResponseEntity<?> registerFarmer(
            @Valid @RequestBody FarmerRegistrationRequest request) {

        try {

            Farmer farmer = farmerService.registerFarmer(request);

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of(
                            "id", farmer.getId(),
                            "farmerCode", farmer.getFarmerCode(),
                            "fullName", farmer.getFullName(),
                            "phone", farmer.getPhone() == null
                                    ? ""
                                    : farmer.getPhone(),
                            "village", farmer.getVillage() == null
                                    ? ""
                                    : farmer.getVillage(),
                            "panchayatId", farmer.getPanchayat().getId(),
                            "panchayatName", farmer.getPanchayat().getName()
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