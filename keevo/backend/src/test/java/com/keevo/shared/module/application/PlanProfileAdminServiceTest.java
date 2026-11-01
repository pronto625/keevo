package com.keevo.shared.module.application;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanProfileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 16.4 — {@link PlanProfileAdminService#update}: the composition change is decided from the row read under its
 * lock, the {@code PROFILE_RESYNC} slot is reserved before the write (a refusal saves nothing), cancelled when the
 * write fails, and nothing is thrown once the profile is committed.
 */
@DisplayName("PlanProfileAdminService.update — re-sync job reservation (Story 16.4)")
class PlanProfileAdminServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private final PlanProfiles profiles = mock(PlanProfiles.class);
    private final PlanProfileStore store = mock(PlanProfileStore.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final AdminJobs jobs = mock(AdminJobs.class);
    private final AdminJobs.Reservation reservation = mock(AdminJobs.Reservation.class);
    private final UUID jobId = UUID.randomUUID();
    private PlanProfileAdminService service;

    @BeforeEach
    void setUp() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jobs.reserve(eq(AdminJobs.Kind.PROFILE_RESYNC), any(), eq(ACTOR))).thenReturn(reservation);
        when(reservation.id()).thenReturn(jobId);
        // what the unlocked read says is never what decides: it claims "no change" everywhere
        when(profiles.profile(any(PlanType.class))).thenAnswer(inv -> stored(Set.of("lcpaid")));
        service = new PlanProfileAdminService(TestModuleCatalogs.lifecycleCatalog(), profiles, store,
                mock(PlanModuleRowSync.class), mock(PlanModuleRowStore.class), tm, jobs);
    }

    private static PlanProfile stored(Set<String> granted) {
        Set<String> withheld = new java.util.TreeSet<>(Set.of("lcpaid", "lcpremium"));
        withheld.removeAll(granted);
        return PlanProfile.of(PlanType.FREE, 1, 500, 2, granted, withheld);
    }

    private PlanProfileAdmin.UpdateResult update(Set<String> modules) {
        return service.update(PlanType.FREE, 1, 500, 2, modules, ACTOR);
    }

    @Test
    @DisplayName("the locked row shows no change → no job reserved, jobId null, profile saved")
    void noChangeNoJob() {
        when(store.findForUpdate(PlanType.FREE)).thenReturn(Optional.of(stored(Set.of("lcpaid"))));

        PlanProfileAdmin.UpdateResult result = update(Set.of("lcpaid"));

        assertThat(result.compositionChanged()).isFalse();
        assertThat(result.jobId()).isNull();
        verify(jobs, never()).reserve(any(), any(), any());
        verify(store).save(any(), eq(ACTOR));
    }

    @Test
    @DisplayName("concurrent change: the unlocked read says unchanged but the locked row differs → reserved before "
            + "the write, submitted after, jobId returned")
    void concurrentChange() {
        when(store.findForUpdate(PlanType.FREE)).thenReturn(Optional.of(stored(Set.of())));

        PlanProfileAdmin.UpdateResult result = update(Set.of("lcpaid"));

        assertThat(result.compositionChanged()).isTrue();
        assertThat(result.jobId()).isEqualTo(jobId);
        var order = inOrder(jobs, store, reservation);
        order.verify(jobs).reserve(eq(AdminJobs.Kind.PROFILE_RESYNC), eq(Map.of("planType", "FREE", "plan",
                "Gratuit")), eq(ACTOR));
        order.verify(store).save(any(), eq(ACTOR));
        order.verify(reservation).submit(any());
        verify(reservation, never()).cancel();
    }

    @Test
    @DisplayName("a re-sync still running (409) → the refusal saves nothing")
    void refusalSavesNothing() {
        when(store.findForUpdate(PlanType.FREE)).thenReturn(Optional.of(stored(Set.of())));
        when(jobs.reserve(any(), any(), any())).thenThrow(new DomainException(ErrorCode.ADMIN_JOB_RUNNING, "x"));

        DomainException refused = catchThrowableOfType(DomainException.class, () -> update(Set.of("lcpaid")));

        assertThat(refused.getDomainCode()).isEqualTo("ADMIN_JOB_RUNNING");
        verify(store, never()).save(any(), any());
        verify(tm).rollback(any());
    }

    @Test
    @DisplayName("the save throws → the reserved slot is cancelled, the error propagates, nothing submitted")
    void saveFailsCancels() {
        when(store.findForUpdate(PlanType.FREE)).thenReturn(Optional.of(stored(Set.of())));
        doThrow(new IllegalStateException("db down")).when(store).save(any(), any());

        catchThrowableOfType(IllegalStateException.class, () -> update(Set.of("lcpaid")));

        verify(reservation).cancel();
        verify(reservation, never()).submit(any());
    }

    @Test
    @DisplayName("the submit fails after the commit (queue full) → no exception: the saved profile and the job id")
    void submitFailsAfterCommit() {
        when(store.findForUpdate(PlanType.FREE)).thenReturn(Optional.of(stored(Set.of())));
        doThrow(new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "plein")).when(reservation).submit(any());

        PlanProfileAdmin.UpdateResult result = update(Set.of("lcpaid"));

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.profile().modules()).extracting(PlanProfileAdmin.ModuleRef::id).containsExactly("lcpaid");
        verify(store).save(any(), eq(ACTOR));
    }
}
