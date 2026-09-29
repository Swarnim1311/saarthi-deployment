package com.saarthi.repository;

import com.saarthi.model.Farmer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface FarmerRepository extends JpaRepository<Farmer, Long> {

    Optional<Farmer> findByFarmerCode(String farmerCode);

    boolean existsByFarmerCode(String farmerCode);

    @EntityGraph(attributePaths = {"panchayat"})
    List<Farmer> findByPanchayatId(Long panchayatId);

    @EntityGraph(attributePaths = {"panchayat"})
    List<Farmer> findByPanchayatIdAndFullNameContainingIgnoreCase(
            Long panchayatId,
            String namePart);

    List<Farmer> findByAssignedOfficialId(Long officialId);
}