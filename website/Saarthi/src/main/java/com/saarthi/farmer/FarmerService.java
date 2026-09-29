package com.saarthi.farmer;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Farmer validation and lookup. Required fields mirror the teammate's
 * {@code saveFarmer}: id, name, crop, crop location, district and state.
 * Land size and sowing date stay optional.
 */
@Service
public class FarmerService {

    private final FarmerRepository repository;

    public FarmerService(FarmerRepository repository) {
        this.repository = repository;
    }

    public List<Farmer> all() {
        return repository.findAll();
    }

    public Farmer byId(String farmerId) {
        return repository.findById(farmerId);
    }

    /**
     * Validate and store a profile.
     *
     * @throws IllegalArgumentException when a required field is missing/blank
     */
    public Farmer save(Farmer farmer) {
        if (farmer == null) {
            throw new IllegalArgumentException("A farmer profile is required.");
        }
        require(farmer.getFarmerId(), "Farmer ID is required.");
        require(farmer.getFarmerName(), "Farmer Name is required.");
        require(farmer.getCrop(), "Crop is required.");
        require(farmer.getCropLocation(), "Crop Location is required.");
        require(farmer.getDistrict(), "District is required.");
        require(farmer.getState(), "State is required.");
        farmer.setFarmerId(farmer.getFarmerId().trim());
        farmer.setFarmerName(farmer.getFarmerName().trim());
        farmer.setCrop(farmer.getCrop().trim());
        farmer.setCropLocation(farmer.getCropLocation().trim());
        farmer.setDistrict(farmer.getDistrict().trim());
        farmer.setState(farmer.getState().trim());
        if (farmer.getLandSize() != null && farmer.getLandSize().isBlank()) {
            farmer.setLandSize(null);
        } else if (farmer.getLandSize() != null) {
            farmer.setLandSize(farmer.getLandSize().trim());
        }
        if (farmer.getSowingDate() != null && farmer.getSowingDate().isBlank()) {
            farmer.setSowingDate(null);
        } else if (farmer.getSowingDate() != null) {
            farmer.setSowingDate(farmer.getSowingDate().trim());
        }
        return repository.save(farmer);
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
}
