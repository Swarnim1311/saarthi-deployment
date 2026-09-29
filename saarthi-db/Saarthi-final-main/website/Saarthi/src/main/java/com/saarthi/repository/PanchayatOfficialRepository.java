package com.saarthi.repository;

import com.saarthi.model.PanchayatOfficial;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PanchayatOfficialRepository extends JpaRepository<PanchayatOfficial, Long> {

    Optional<PanchayatOfficial> findByUsername(String username);

    List<PanchayatOfficial> findByPanchayatId(Long panchayatId);
}
