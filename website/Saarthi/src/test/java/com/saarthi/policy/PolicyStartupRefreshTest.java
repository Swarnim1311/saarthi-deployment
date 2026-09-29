package com.saarthi.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Startup refresh fires once in the background and never blocks startup. */
class PolicyStartupRefreshTest {

    @Test
    void enabledTriggersOneBackgroundRefresh() {
        PolicyRefreshService service = mock(PolicyRefreshService.class);
        when(service.refresh()).thenReturn(Map.of("status", "success", "saved", 1, "total", 1));
        new PolicyStartupRefresh(service, true).run(null);
        verify(service, timeout(5_000).times(1)).refresh();
    }

    @Test
    void disabledSkipsRefresh() throws Exception {
        PolicyRefreshService service = mock(PolicyRefreshService.class);
        new PolicyStartupRefresh(service, false).run(null);
        Thread.sleep(200);
        verify(service, never()).refresh();
    }

    @Test
    void failingRefreshNeverThrows() throws Exception {
        PolicyRefreshService service = mock(PolicyRefreshService.class);
        when(service.refresh()).thenThrow(new RuntimeException("boom"));
        // Must return immediately and must not propagate the failure.
        new PolicyStartupRefresh(service, true).run(null);
        long deadline = System.currentTimeMillis() + 15_000;
        while (mockingDetails(service).getInvocations().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        verify(service, timeout(1_000).times(1)).refresh();
    }
}
