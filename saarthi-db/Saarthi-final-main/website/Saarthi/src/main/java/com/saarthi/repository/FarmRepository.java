package com.saarthi.repository;

import com.saarthi.model.Farm;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FarmRepository extends JpaRepository<Farm, Long> {

    List<Farm> findByFarmerId(Long farmerId);
}
