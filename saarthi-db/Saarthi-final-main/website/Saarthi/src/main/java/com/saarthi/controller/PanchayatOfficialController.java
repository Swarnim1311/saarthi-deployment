package com.saarthi.controller;

import com.saarthi.dto.PanchayatOfficialRegistrationRequest;
import com.saarthi.model.Farmer;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.service.PanchayatOfficialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.saarthi.model.CropPlanting;
import com.saarthi.model.Farm;
import com.saarthi.dto.OfficialLoginRequest;
import com.saarthi.security.JwtService;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/panchayat-officials")
public class PanchayatOfficialController {

    private final PanchayatOfficialService officialService;
    private final JwtService jwtService;

    public PanchayatOfficialController(
            PanchayatOfficialService officialService,
            JwtService jwtService) {
        this.officialService = officialService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public ResponseEntity<?> registerOfficial(
            @Valid @RequestBody PanchayatOfficialRegistrationRequest request) {

        try {
            PanchayatOfficial official =
                    officialService.registerOfficial(request);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "id", official.getId(),
                            "fullName", official.getFullName(),
                            "username", official.getUsername(),
                            "role", official.getRole(),
                            "active", official.isActive(),
                            "panchayatId",
                            official.getPanchayat().getId(),
                            "panchayatName",
                            official.getPanchayat().getName(),
                            "blockName",
                            official.getPanchayat().getBlockName()
                    ));

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{officialId}/farmers")
    public ResponseEntity<?> getFarmers(
            @PathVariable Long officialId) {

        try {
            List<Farmer> farmers =
                    officialService.getFarmersForOfficial(officialId);

            return ResponseEntity.ok(
                    farmers.stream()
                            .map(farmer -> Map.of(
                                    "id", farmer.getId(),
                                    "farmerCode", farmer.getFarmerCode(),
                                    "fullName", farmer.getFullName(),
                                    "phone",
                                    farmer.getPhone() == null
                                            ? ""
                                            : farmer.getPhone(),
                                    "village",
                                    farmer.getVillage() == null
                                            ? ""
                                            : farmer.getVillage(),
                                    "panchayatId",
                                    farmer.getPanchayat().getId(),
                                    "panchayatName",
                                    farmer.getPanchayat().getName()
                            ))
                            .toList()
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/{officialId}/farmers/{farmerId}/farms")
    public ResponseEntity<?> getFarmerFarms(
            @PathVariable Long officialId,
            @PathVariable Long farmerId) {

        try {
            List<Farm> farms =
                    officialService.getFarmsForOfficial(
                            officialId,
                            farmerId);

            return ResponseEntity.ok(
                    farms.stream()
                            .map(farm -> Map.of(
                                    "id", farm.getId(),
                                    "farmerId", farm.getFarmer().getId(),
                                    "plotLabel",
                                    farm.getPlotLabel() == null
                                            ? "" : farm.getPlotLabel(),
                                    "areaAcres",
                                    farm.getAreaAcres() == null
                                            ? 0 : farm.getAreaAcres(),
                                    "village",
                                    farm.getVillage() == null
                                            ? "" : farm.getVillage(),
                                    "blockName",
                                    farm.getBlockName(),
                                    "panchayatName",
                                    farm.getPanchayatName(),
                                    "soilTexture",
                                    farm.getSoilTexture() == null
                                            ? "" : farm.getSoilTexture(),
                                    "irrigationSource",
                                    farm.getIrrigationSource() == null
                                            ? "" : farm.getIrrigationSource()
                            ))
                            .toList()
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{officialId}/farms/{farmId}/crops")
    public ResponseEntity<?> getFarmCrops(
            @PathVariable Long officialId,
            @PathVariable Long farmId) {

        try {
            List<CropPlanting> crops =
                    officialService.getCropsForOfficial(
                            officialId,
                            farmId);

            return ResponseEntity.ok(
                    crops.stream()
                            .map(crop -> Map.of(
                                    "id", crop.getId(),
                                    "farmId", crop.getFarm().getId(),
                                    "cropName", crop.getCropName(),
                                    "variety",
                                    crop.getVariety() == null
                                            ? "" : crop.getVariety(),
                                    "season", crop.getSeason(),
                                    "cropYear", crop.getCropYear(),
                                    "sowingDate",
                                    crop.getSowingDate() == null
                                            ? "" : crop.getSowingDate().toString(),
                                    "harvestDate",
                                    crop.getHarvestDate() == null
                                            ? "" : crop.getHarvestDate().toString()
                            ))
                            .toList()
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{officialId}/farmers/{farmerId}")
    public ResponseEntity<?> getFarmerDetails(
            @PathVariable Long officialId,
            @PathVariable Long farmerId) {

        try {
            Farmer farmer =
                    officialService.getFarmerDetailsForOfficial(
                            officialId,
                            farmerId);

            return ResponseEntity.ok(
                    Map.of(
                            "id", farmer.getId(),
                            "farmerCode", farmer.getFarmerCode(),
                            "fullName", farmer.getFullName(),
                            "phone", farmer.getPhone() == null
                                    ? "" : farmer.getPhone(),
                            "village", farmer.getVillage() == null
                                    ? "" : farmer.getVillage(),
                            "panchayatId", farmer.getPanchayat().getId(),
                            "panchayatName", farmer.getPanchayat().getName(),
                            "blockName", farmer.getPanchayat().getBlockName(),
                            "notes", farmer.getNotes() == null
                                    ? "" : farmer.getNotes()
                    )
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{officialId}/farmers/search")
    public ResponseEntity<?> searchFarmers(
            @PathVariable Long officialId,
            @RequestParam(required = false) String name) {

        try {
            List<Farmer> farmers =
                    officialService.searchFarmersForOfficial(
                            officialId,
                            name);

            return ResponseEntity.ok(
                    farmers.stream()
                            .map(farmer -> Map.of(
                                    "id", farmer.getId(),
                                    "farmerCode", farmer.getFarmerCode(),
                                    "fullName", farmer.getFullName(),
                                    "phone", farmer.getPhone() == null
                                            ? "" : farmer.getPhone(),
                                    "village", farmer.getVillage() == null
                                            ? "" : farmer.getVillage(),
                                    "panchayatId",
                                    farmer.getPanchayat().getId(),
                                    "panchayatName",
                                    farmer.getPanchayat().getName()
                            ))
                            .toList()
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{officialId}/dashboard")
    public ResponseEntity<?> getDashboard(
            @PathVariable Long officialId) {

        try {

            return ResponseEntity.ok(
                    officialService.getDashboardSummary(officialId)
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
        }
        @PostMapping("/login")
        public ResponseEntity<?> login(
                @Valid @RequestBody OfficialLoginRequest request) {

        try {

                String token = officialService.login(
                        request.getUsername(),
                        request.getPassword(),
                        jwtService
                );

                return ResponseEntity.ok(
                        Map.of(
                                "token", token,
                                "tokenType", "Bearer"
                        )
                );

        } catch (IllegalArgumentException e) {

                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", e.getMessage()));
        }
        }
}