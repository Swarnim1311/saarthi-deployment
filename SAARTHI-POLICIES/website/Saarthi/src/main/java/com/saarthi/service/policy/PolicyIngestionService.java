package com.saarthi.service.policy;

import com.saarthi.model.PolicyAlert;
import com.saarthi.repository.PolicyAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PolicyIngestionService {

    private final PolicyAlertRepository policyAlertRepository;
    private final List<PolicySource> policySources;

    public PolicyIngestionService(
            PolicyAlertRepository policyAlertRepository,
            List<PolicySource> policySources
    ) {
        this.policyAlertRepository = policyAlertRepository;
        this.policySources = policySources;
    }

    @Transactional
    public int ingestPolicies() {

        int savedCount = 0;

        for (PolicySource source : policySources) {

            List<PolicySourceItem> items = source.fetch();

            for (PolicySourceItem item : items) {

                String externalId = buildExternalId(item);

                if (policyAlertRepository
                        .findByExternalId(externalId)
                        .isPresent()) {
                    continue;
                }

                PolicyAlert alert = new PolicyAlert();

                alert.setTitle(item.getTitle());
                alert.setSummary(item.getSummary());
                alert.setSource(item.getSource());
                alert.setSourceUrl(item.getSourceUrl());
                alert.setPublishedDate(item.getPublishedDate());

                alert.setCategory("AGRICULTURE");
                alert.setVerificationStatus("PENDING");
                alert.setApproved(false);
                alert.setExternalId(externalId);

                policyAlertRepository.save(alert);

                savedCount++;
            }
        }

        return savedCount;
    }

    private String buildExternalId(PolicySourceItem item) {

        return item.getSource()
                + ":"
                + item.getSourceUrl();
    }
}