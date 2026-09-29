package com.saarthi.policy;

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

                if (item.getTitle() == null || item.getTitle().isBlank()
                        || item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
                    continue;
                }

                PolicyAlert alert = new PolicyAlert();

                alert.setTitle(item.getTitle().trim());
                alert.setSummary(item.getSummary() == null ? "" : item.getSummary().trim());
                alert.setSource(item.getSource());
                alert.setSourceUrl(item.getSourceUrl().trim());
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

    /**
     * Canonical identity for one official release. PIB serves the same release
     * under two URL shapes ({@code PressReleaseIframePage.aspx} from the RSS
     * feed and {@code PressReleaseDetail.aspx} from the listing); both carry
     * the same {@code PRID}, so PRID-bearing URLs collapse to one id and a
     * repeated refresh never stores the same release twice.
     */
    static String buildExternalId(PolicySourceItem item) {
        String source = item.getSource() == null ? "" : item.getSource().trim().toUpperCase();
        String url = item.getSourceUrl() == null ? "" : item.getSourceUrl().trim();
        java.util.regex.Matcher prid =
                java.util.regex.Pattern.compile("[?&]PRID=(\\d+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        if (prid.find()) {
            return source + ":PRID:" + prid.group(1);
        }
        return source + ":" + url;
    }
}
