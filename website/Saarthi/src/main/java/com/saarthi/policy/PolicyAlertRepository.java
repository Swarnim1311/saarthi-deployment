package com.saarthi.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyAlertRepository
        extends JpaRepository<PolicyAlert, Long> {

    Optional<PolicyAlert> findByExternalId(String externalId);

    List<PolicyAlert> findTop50ByApprovedTrueOrderByPublishedDateDesc();

    List<PolicyAlert> findTop50ByOrderByPublishedDateDesc();
}
