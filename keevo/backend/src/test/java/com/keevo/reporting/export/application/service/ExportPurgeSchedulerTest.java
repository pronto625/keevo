package com.keevo.reporting.export.application.service;

import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExportPurgeScheduler (v1s-18-1)")
class ExportPurgeSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Mock TenantRepository tenantRepository;
    @Mock ExportJobRepository jobRepository;
    @Mock ExportFileStorage storage;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ExportPurgeScheduler scheduler() {
        return new ExportPurgeScheduler(IdentityAuthApiTestSupport.tenantQueryPort(tenantRepository), jobRepository, storage, new ExportProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExportJob ready(Instant expiresAt) {
        ExportJob j = ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.CSV, List.of("stores"),
                ExportFilter.none(), "f.csv", "text/csv", NOW.minus(Duration.ofDays(2)));
        j.start(NOW.minus(Duration.ofDays(2)));
        j.complete(10, 100, "kv_aaaaaa/" + j.getId() + ".csv", NOW.minus(Duration.ofDays(2)), expiresAt);
        return j;
    }

    @Test
    @DisplayName("READY past expiresAt → file deleted, EXPIRED; stale PENDING/RUNNING → FAILED EXPORT_INTERRUPTED")
    void purgeTenant() {
        ExportJob expired = ready(NOW.minusSeconds(1));
        ExportJob stale = ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.XLSX,
                List.of("stores"), ExportFilter.none(), "f.xlsx", "x", NOW.minus(Duration.ofHours(3)));
        List<String> tenantsSeen = new ArrayList<>();
        when(jobRepository.findReadyExpiredAt(NOW)).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.getCurrentTenant());
            return List.of(expired);
        });
        when(jobRepository.findStale(NOW.minus(Duration.ofHours(2)))).thenReturn(List.of(stale));

        scheduler().purgeTenant("kv_aaaaaa");

        assertThat(tenantsSeen).containsExactly("kv_aaaaaa");
        verify(storage).delete(expired.getStoragePath());
        assertThat(expired.getStatus()).isEqualTo(ExportJobStatus.EXPIRED);
        assertThat(stale.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(stale.getErrorCode()).isEqualTo("EXPORT_INTERRUPTED");
        verify(storage).deletePartials("kv_aaaaaa", stale.getId());
        verify(jobRepository, times(2)).save(any());
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("purge(): every tenant but DELETED (suspended files expire too); one tenant's failure does not stop the loop")
    void purgeLoopsActiveTenantsAndContinuesOnError() {
        Tenant a = tenant("kv_aaaaaa", TenantStatus.ACTIVE);
        Tenant b = tenant("kv_bbbbbb", TenantStatus.SUSPENDED);
        Tenant c = tenant("kv_cccccc", TenantStatus.DELETION_PENDING);
        Tenant d = tenant("kv_dddddd", TenantStatus.DELETED);
        when(tenantRepository.findAll()).thenReturn(List.of(a, b, c, d));
        List<String> seen = new ArrayList<>();
        when(jobRepository.findReadyExpiredAt(any())).thenAnswer(inv -> {
            String t = TenantContext.getCurrentTenant();
            seen.add(t);
            if (t.equals("kv_aaaaaa")) throw new IllegalStateException("relation export_jobs does not exist");
            return List.of();
        });
        when(jobRepository.findStale(any())).thenReturn(List.of());

        scheduler().purge();

        assertThat(seen).containsExactly("kv_aaaaaa", "kv_bbbbbb", "kv_cccccc");
        verify(jobRepository, never()).save(any());
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("startup recovery: every PENDING / RUNNING job of non-DELETED tenants → FAILED EXPORT_INTERRUPTED, partials deleted")
    void startupRecovery() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant("kv_aaaaaa", TenantStatus.ACTIVE),
                tenant("kv_bbbbbb", TenantStatus.SUSPENDED), tenant("kv_dddddd", TenantStatus.DELETED)));
        ExportJob pending = ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.CSV,
                List.of("stores"), ExportFilter.none(), "f.csv", "text/csv", NOW.minusSeconds(30));
        ExportJob running = ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.CSV,
                List.of("stores"), ExportFilter.none(), "f.csv", "text/csv", NOW.minusSeconds(60));
        running.start(NOW.minusSeconds(10));
        List<String> seen = new ArrayList<>();
        when(jobRepository.findActive()).thenAnswer(inv -> {
            String t = TenantContext.getCurrentTenant();
            seen.add(t);
            return t.equals("kv_aaaaaa") ? List.of(pending, running) : List.of();
        });

        scheduler().recoverInterruptedJobs();

        assertThat(seen).containsExactly("kv_aaaaaa", "kv_bbbbbb");
        assertThat(pending.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(running.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(running.getErrorCode()).isEqualTo("EXPORT_INTERRUPTED");
        verify(storage).deletePartials("kv_aaaaaa", pending.getId());
        verify(storage).deletePartials("kv_aaaaaa", running.getId());
        verify(jobRepository, times(2)).save(any());
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    private static Tenant tenant(String schema, TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "KV-" + schema.substring(3).toUpperCase(), schema, status,
                PlanType.FREE, NOW);
    }
}
