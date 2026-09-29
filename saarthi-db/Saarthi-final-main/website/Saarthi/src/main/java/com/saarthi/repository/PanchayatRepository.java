package com.saarthi.repository;

import com.saarthi.model.Panchayat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PanchayatRepository extends JpaRepository<Panchayat, Long> {

    Optional<Panchayat> findByBlockNameAndName(String blockName, String name);

    List<Panchayat> findByBlockName(String blockName);
}
