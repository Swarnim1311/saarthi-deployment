package com.saarthi.service;

import com.saarthi.model.CropPlanting;
import com.saarthi.model.Farm;
import com.saarthi.repository.CropPlantingRepository;
import com.saarthi.repository.FarmRepository;
import com.saarthi.dto.PanchayatOfficialRegistrationRequest;
import com.saarthi.model.Farmer;
import com.saarthi.model.Panchayat;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.repository.FarmerRepository;
import com.saarthi.repository.PanchayatOfficialRepository;
import com.saarthi.repository.PanchayatRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import com.saarthi.security.JwtService;

@Service
public class PanchayatOfficialService {

    private final PanchayatOfficialRepository officialRepository;
    private final PanchayatRepository panchayatRepository;
    private final FarmerRepository farmerRepository;
    private final FarmRepository farmRepository;
private final CropPlantingRepository cropPlantingRepository;
private final PasswordEncoder passwordEncoder;

    public PanchayatOfficialService(
            PanchayatOfficialRepository officialRepository,
            PanchayatRepository panchayatRepository,
            FarmerRepository farmerRepository,
            FarmRepository farmRepository,
            CropPlantingRepository cropPlantingRepository,
            PasswordEncoder passwordEncoder) {

        this.officialRepository = officialRepository;
        this.panchayatRepository = panchayatRepository;
        this.farmerRepository = farmerRepository;
        this.farmRepository = farmRepository;
        this.cropPlantingRepository = cropPlantingRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public PanchayatOfficial registerOfficial(
            PanchayatOfficialRegistrationRequest request) {

        Panchayat panchayat = panchayatRepository.findById(request.getPanchayatId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Panchayat not found: " + request.getPanchayatId()));

        String username = normalizeRequired(request.getUsername());

        if (officialRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException(
                "Username already exists: " + username);
        }

        PanchayatOfficial official = new PanchayatOfficial();

        official.setFullName(normalizeRequired(request.getFullName()));
        official.setUsername(username);
        official.setPasswordHash(
                passwordEncoder.encode(request.getPassword())
        );
        official.setPanchayat(panchayat);
        official.setRole("PANCHAYAT_OFFICIAL");
        official.setActive(true);

        return officialRepository.save(official);
    }
    @Transactional
    public List<Farm> getFarmsForOfficial(
            Long officialId,
            Long farmerId) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        Farmer farmer = farmerRepository.findById(farmerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Farmer not found: " + farmerId));

        verifyFarmerBelongsToOfficialPanchayat(official, farmer);

        return farmRepository.findByFarmerId(farmerId);
    }
    @Transactional
    public List<CropPlanting> getCropsForOfficial(
            Long officialId,
            Long farmId) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Farm not found: " + farmId));

        Farmer farmer = farm.getFarmer();

        if (farmer == null) {
            throw new IllegalArgumentException(
                    "Farm is not associated with a farmer");
        }

        verifyFarmerBelongsToOfficialPanchayat(official, farmer);

        return cropPlantingRepository.findByFarmId(farmId);
    }

    private void verifyFarmerBelongsToOfficialPanchayat(
            PanchayatOfficial official,
            Farmer farmer) {

        if (farmer.getPanchayat() == null) {
            throw new IllegalArgumentException(
                    "Farmer is not associated with a Panchayat");
        }

        if (!farmer.getPanchayat().getId()
                .equals(official.getPanchayat().getId())) {

            throw new IllegalArgumentException(
                    "Farmer does not belong to the official's Panchayat");
        }
    }

    @Transactional
    public List<Farmer> getFarmersForOfficial(Long officialId) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        return farmerRepository.findByPanchayatId(
                official.getPanchayat().getId());
    }

    private PanchayatOfficial getActiveOfficial(Long officialId) {

        PanchayatOfficial official = officialRepository.findById(officialId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Official not found: " + officialId));

        if (!official.isActive()) {
            throw new IllegalArgumentException(
                    "Official account is inactive");
        }

        if (official.getPanchayat() == null) {
            throw new IllegalArgumentException(
                    "Official is not associated with a Panchayat");
        }

        return official;
    }

    private String normalizeRequired(String value) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Required field is missing");
        }

        return value.trim();
    }

    @Transactional
    public Farmer getFarmerDetailsForOfficial(
            Long officialId,
            Long farmerId) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        Farmer farmer = farmerRepository.findById(farmerId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Farmer not found: " + farmerId));

        verifyFarmerBelongsToOfficialPanchayat(official, farmer);

        return farmer;
    }

    @Transactional
    public List<Farmer> searchFarmersForOfficial(
            Long officialId,
            String name) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        String searchTerm = name == null ? "" : name.trim();

        if (searchTerm.isEmpty()) {
            return farmerRepository.findByPanchayatId(
                    official.getPanchayat().getId());
        }

        return farmerRepository
                .findByPanchayatIdAndFullNameContainingIgnoreCase(
                        official.getPanchayat().getId(),
                        searchTerm);
    }

    @Transactional
    public Map<String, Object> getDashboardSummary(Long officialId) {

        PanchayatOfficial official = getActiveOfficial(officialId);

        Long panchayatId = official.getPanchayat().getId();

        List<Farmer> farmers =
                farmerRepository.findByPanchayatId(panchayatId);

        int totalFarms = 0;
        int totalCropPlantings = 0;

        for (Farmer farmer : farmers) {

            List<Farm> farms =
                    farmRepository.findByFarmerId(farmer.getId());

            totalFarms += farms.size();

            for (Farm farm : farms) {
                totalCropPlantings +=
                        cropPlantingRepository
                                .findByFarmId(farm.getId())
                                .size();
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();

        summary.put("officialId", official.getId());
        summary.put("officialName", official.getFullName());

        summary.put("panchayatId", official.getPanchayat().getId());
        summary.put("panchayatName", official.getPanchayat().getName());
        summary.put("blockName", official.getPanchayat().getBlockName());

        summary.put("totalFarmers", farmers.size());
        summary.put("totalFarms", totalFarms);
        summary.put("totalCropPlantings", totalCropPlantings);

        return summary;
    }
    public String login(
                String username,
                String password,
                JwtService jwtService) {

        PanchayatOfficial official =
                officialRepository.findByUsername(username.trim())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Invalid username or password"));

        if (!official.isActive()) {
                throw new IllegalArgumentException(
                        "Official account is inactive");
        }

        if (official.getPasswordHash() == null ||
                !passwordEncoder.matches(
                        password,
                        official.getPasswordHash())) {

                throw new IllegalArgumentException(
                        "Invalid username or password");
        }

        return jwtService.generateToken(
                official.getUsername()
        );
        }
}