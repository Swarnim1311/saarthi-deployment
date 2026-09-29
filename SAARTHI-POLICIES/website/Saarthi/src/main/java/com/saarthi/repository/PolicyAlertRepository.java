package com.saarthi.repository;

import com.saarthi.model.PolicyAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyAlertRepository
        extends JpaRepository<PolicyAlert, Long> {

    Optional<PolicyAlert> findByExternalId(String externalId);

    List<PolicyAlert> findTop50ByApprovedTrueOrderByPublishedDateDesc();

    List<PolicyAlert> findTop50ByOrderByPublishedDateDesc();
}
