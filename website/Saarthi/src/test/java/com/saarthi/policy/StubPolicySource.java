package com.saarthi.policy;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Test-only stub source; never registered as production code. */
@Component("policyTestStubSource")
public class StubPolicySource implements PolicySource {

    private final List<PolicySourceItem> items;

    public StubPolicySource() {
        this.items = List.of();
    }

    public StubPolicySource(List<PolicySourceItem> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String getSourceName() {
        return "TEST";
    }

    @Override
    public List<PolicySourceItem> fetch() {
        return items;
    }

    static PolicySourceItem item(String title, String url, LocalDate date) {
        return new PolicySourceItem(title, "summary", "TEST", url, date);
    }
}
