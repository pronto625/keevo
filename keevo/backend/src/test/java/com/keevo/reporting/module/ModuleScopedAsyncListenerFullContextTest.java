package com.keevo.reporting.module;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.event.catalog.DomainEvent;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleScoped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.9 — the {@code @ModuleScoped} gate on an <b>asynchronous</b> {@code @ApplicationModuleListener} in the real
 * context (async executor thread, no request bound: the module state is read from the database; Modulith registry
 * completion). A probe listener scoped to {@code reporting}: DISABLED for the tenant → not invoked, publication
 * completed; enabled (free rule) → invoked with the tenant bound.
 */
@Import(ModuleScopedAsyncListenerFullContextTest.ProbeConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("@ModuleScoped async module listener — full context (Story 13.9)")
class ModuleScopedAsyncListenerFullContextTest extends FullContextIntegrationTest {

    public record ScopedProbeEvent(UUID probeId, String tenantId, Instant occurredAt, UUID actorId)
            implements DomainEvent {
    }

    public static class ScopedProbeListener {
        final Map<UUID, String> calls = new ConcurrentHashMap<>();

        public Map<UUID, String> calls() { // the bean is a proxy: read through an accessor
            return calls;
        }

        @ApplicationModuleListener
        @ModuleScoped("reporting")
        public void on(ScopedProbeEvent event) {
            calls.put(event.probeId(), String.valueOf(TenantContext.getCurrentTenant()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        ScopedProbeListener moduleScopedProbeListener() {
            return new ScopedProbeListener();
        }
    }

    @Autowired ScopedProbeListener listener;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired TenantFactory tenantFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
    }

    private UUID publish() {
        UUID probeId = UUID.randomUUID();
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new ScopedProbeEvent(probeId, schema, Instant.now(), UUID.randomUUID())));
        return probeId;
    }

    /** Waits (≤ 20 s) for the probe's publication to be completed; returns its completion date. */
    private Object awaitCompletion(UUID probeId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT completion_date FROM public.event_publication "
                    + "WHERE listener_id LIKE ? AND serialized_event LIKE ?", "%ScopedProbeListener%",
                    "%" + probeId + "%");
            if (!rows.isEmpty() && rows.get(0).get("completion_date") != null) {
                return rows.get(0).get("completion_date");
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    @DisplayName("reporting DISABLED → async listener not invoked, publication completed")
    void disabledSkipped() throws InterruptedException {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'reporting', "
                + "'DISABLED', 'FREE')", tenantId);

        UUID probeId = publish();

        assertThat(awaitCompletion(probeId)).isNotNull();
        assertThat(listener.calls()).doesNotContainKey(probeId);
    }

    @Test
    @DisplayName("reporting enabled (free rule) → async listener invoked with the tenant bound")
    void enabledDelivered() throws InterruptedException {
        UUID probeId = publish();

        assertThat(awaitCompletion(probeId)).isNotNull();
        assertThat(listener.calls()).containsEntry(probeId, schema);
    }
}
