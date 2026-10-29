package com.keevo.identity.auth.adapter.in.scheduling;

import com.keevo.identity.auth.application.service.TenantDeletionService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Story 12.2f — the daily trigger only delegates to the deletion routine. */
class AccountDeletionSchedulerTest {

    @Test
    void executeDeletions_delegatesToTheService() {
        TenantDeletionService service = mock(TenantDeletionService.class);

        new AccountDeletionScheduler(service).executeDeletions();

        verify(service).executeDeletions();
        verifyNoMoreInteractions(service);
    }
}
