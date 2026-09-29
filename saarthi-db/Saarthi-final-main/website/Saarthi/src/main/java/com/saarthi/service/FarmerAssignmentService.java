package com.saarthi.service;

import com.saarthi.model.Farmer;
import com.saarthi.model.PanchayatOfficial;
import com.saarthi.repository.FarmerRepository;
import com.saarthi.repository.PanchayatOfficialRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns monitoring responsibility for farmers to Panchayat officials.
 *
 * <p>Rule enforced here: an official may only monitor farmers of their OWN
 * Panchayat. Cross-Panchayat assignment is rejected. Assignment only
 * changes {@code Farmer.assignedOfficial} (the current responsibility);
 * the original {@code registeredByOfficial} audit link is never touched,
 * and no new farmer row is created on reassignment.
 *
 * <p>No controller exposes this yet; authentication/authorization arrive
 * in a later phase.
 */
@Service
public class FarmerAssignmentService {

    private final FarmerRepository farmers;
    private final PanchayatOfficialRepository officials;

    public FarmerAssignmentService(FarmerRepository farmers,
            PanchayatOfficialRepository officials) {
        this.farmers = farmers;
        this.officials = officials;
    }

    /**
     * Assign (or reassign) a farmer to an official of the same Panchayat.
     *
     * @throws IllegalArgumentException if the farmer or official does not
     *         exist, or they belong to different Panchayats.
     */
    @Transactional
    public Farmer assignOfficial(Long farmerId, Long officialId) {
        Farmer farmer = farmers.findById(farmerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown farmer id: " + farmerId));
        PanchayatOfficial official = officials.findById(officialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown official id: " + officialId));
        Long farmerPanchayat = farmer.getPanchayat().getId();
        Long officialPanchayat = official.getPanchayat().getId();
        if (!Objects.equals(farmerPanchayat, officialPanchayat)) {
            throw new IllegalArgumentException(
                    "Official " + officialId + " belongs to panchayat " + officialPanchayat
                    + " and cannot monitor farmer " + farmerId
                    + " of panchayat " + farmerPanchayat);
        }
        farmer.setAssignedOfficial(official);
        return farmers.save(farmer);
    }

    /** Clear the current assignment; the farmer row itself is untouched. */
    @Transactional
    public Farmer unassignOfficial(Long farmerId) {
        Farmer farmer = farmers.findById(farmerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown farmer id: " + farmerId));
        farmer.setAssignedOfficial(null);
        return farmers.save(farmer);
    }

    /** All farmers currently assigned to one official. */
    @Transactional(readOnly = true)
    public List<Farmer> assignedFarmers(Long officialId) {
        if (!officials.existsById(officialId)) {
            throw new IllegalArgumentException("Unknown official id: " + officialId);
        }
        return farmers.findByAssignedOfficialId(officialId);
    }
}
