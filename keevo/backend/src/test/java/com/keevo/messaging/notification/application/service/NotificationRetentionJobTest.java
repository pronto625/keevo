package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository.Purge;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 19.11 — {@link NotificationRetentionJob}: cut-off, per-tenant transaction and failure isolation, summary. */
@DisplayName("NotificationRetentionJob (Story 19.11)")
class NotificationRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:50:00Z");

    private final PlanModuleRowStore tenants = mock(PlanModuleRowStore.class);
    private final NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
    private final List<String> outcomes = new ArrayList<>();
    private NotificationRetentionJob job;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tx = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                outcomes.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                outcomes.add("rollback");
            }
        };
        job = new NotificationRetentionJob(tenants, inbox, tx, Clock.fixed(NOW, ZoneOffset.UTC), 90);
    }

    private static LiveTenant tenant(String schema, boolean present) {
        return new LiveTenant(UUID.randomUUID(), "KV-" + schema.substring(3).toUpperCase(), schema, present);
    }

    @Test
    @DisplayName("each tenant purged in its own transaction with the read-days cut-off; one failure rolls back that "
            + "tenant only; schemas without inbox, absent or invalid are skipped; summary counts")
    void perTenantIsolationAndSummary() {
        LiveTenant a = tenant("kv_aaaaaa", true);
        LiveTenant broken = tenant("kv_bbbbbb", true);
        LiveTenant noInbox = tenant("kv_cccccc", true);
        LiveTenant gone = tenant("kv_dddddd", false);
        LiveTenant platform = new LiveTenant(UUID.randomUUID(), "KV-PLATFO", "public", true);
        LiveTenant c = tenant("kv_eeeeee", true);
        when(tenants.liveTenants()).thenReturn(List.of(a, broken, noInbox, gone, platform, c));
        when(inbox.schemasWithInbox()).thenReturn(Set.of("kv_aaaaaa", "kv_bbbbbb", "kv_dddddd", "kv_eeeeee"));
        Instant cutoff = NOW.minusSeconds(90L * 86_400);
        when(inbox.purge("kv_aaaaaa", a.id(), cutoff)).thenReturn(new Purge(3, 1));
        when(inbox.purge("kv_bbbbbb", broken.id(), cutoff)).thenThrow(new IllegalStateException("boom"));
        when(inbox.purge("kv_eeeeee", c.id(), cutoff)).thenReturn(new Purge(2, 4));

        NotificationRetentionJob.Report report = job.purge();

        assertThat(report.tenants()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(3);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.read()).isEqualTo(5);
        assertThat(report.departed()).isEqualTo(5);
        assertThat(report.ms()).isNotNegative();
        // a, broken (rolled back), c — no transaction for the schemas without inbox, absent or platform
        assertThat(outcomes).containsExactly("commit", "rollback", "commit");
        verify(inbox, times(1)).schemasWithInbox(); // one catalogue query per run, not one per tenant
        verify(inbox, never()).purge(eq("kv_cccccc"), any(), any());
        verify(inbox, never()).purge(eq("kv_dddddd"), any(), any());
        verify(inbox, never()).purge(eq("public"), any(), any());
    }

    @Test
    @DisplayName("the listing itself failing reaches the caller of purge(), never the scheduler thread")
    void listingFailureContainedByScheduledEntry() {
        when(tenants.liveTenants()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(job::purge).isInstanceOf(IllegalStateException.class);
        job.scheduled(); // logged, not thrown
    }

    @Test
    @DisplayName("no live tenant: an empty report")
    void noTenant() {
        when(tenants.liveTenants()).thenReturn(List.of());
        when(inbox.schemasWithInbox()).thenReturn(Set.of());

        assertThat(job.purge()).extracting(NotificationRetentionJob.Report::tenants,
                NotificationRetentionJob.Report::failures).containsExactly(0, 0);
    }

    @Test
    @DisplayName("read-days below 1 refused at construction")
    void invalidReadDays() {
        assertThatThrownBy(() -> new NotificationRetentionJob(tenants, inbox, mock(PlatformTransactionManager.class),
                Clock.systemUTC(), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
