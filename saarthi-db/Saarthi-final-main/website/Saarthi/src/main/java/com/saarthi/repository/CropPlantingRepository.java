package com.saarthi.repository;

import com.saarthi.model.CropPlanting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CropPlantingRepository extends JpaRepository<CropPlanting, Long> {

    List<CropPlanting> findByFarmId(Long farmId);

    List<CropPlanting> findByFarmIdOrderByCropYearDescCreatedAtDescIdDesc(Long farmId);

    /**
     * Current planting: latest by agricultural year, then by creation order
     * ({@code createdAt}, with {@code id} as the final tiebreak so rows
     * created within the same timestamp tick still resolve deterministically
     * to the later insert). History rows are never updated; this query
     * defines "current".
     */
    Optional<CropPlanting> findFirstByFarmIdOrderByCropYearDescCreatedAtDescIdDesc(Long farmId);
}
