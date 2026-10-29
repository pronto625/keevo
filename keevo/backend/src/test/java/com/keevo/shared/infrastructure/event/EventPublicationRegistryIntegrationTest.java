package com.keevo.shared.infrastructure.event;

import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.catalog.DomainEvent;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story 12.4 — the Spring Modulith JDBC event publication registry against PostgreSQL (full application
 * context, real {@code notificationExecutor}, real module-listener interceptor), rows of the spec's I/O matrix:
 * nominal (after commit → listener → publication completed), rollback (no row, no call), failing listener
 * (incomplete row → resubmission → invoked once more → completed), two tenants concurrently (each listener in
 * its own tenant schema: context + search_path of the listener's transaction), and {@code @DeliverOnce}
 * (a replayed publication is not delivered twice).
 */
@Import(EventPublicationRegistryIntegrationTest.ProbeConfig.class)
class EventPublicationRegistryIntegrationTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** Test-only catalogue-shaped event (round-trips through the registry's Jackson serialisation). */
    public record ProbeEvent(UUID probeId, String tenantId, Instant occurredAt, UUID actorId) implements DomainEvent {
        static ProbeEvent of(String tenant) {
            return new ProbeEvent(UUID.randomUUID(), tenant, Instant.now(), UUID.randomUUID());
        }
    }

    /** Second event type, consumed by the {@code @DeliverOnce} probe only. */
    public record OnceProbeEvent(UUID probeId, String tenantId, Instant occurredAt, UUID actorId)
            implements DomainEvent {
        static OnceProbeEvent of(String tenant) {
            return new OnceProbeEvent(UUID.randomUUID(), tenant, Instant.now(), UUID.randomUUID());
        }
    }

    /** What a listener invocation observed. */
    public record Seen(String tenant, String searchPath, String thread) {}

    public static class ProbeListener {
        final Map<UUID, List<Seen>> calls = new ConcurrentHashMap<>();
        final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();
        final Map<UUID, List<Seen>> onceCalls = new ConcurrentHashMap<>();
        private final JdbcTemplate jdbc;

        public ProbeListener(DataSource dataSource) {
            this.jdbc = new JdbcTemplate(dataSource);
        }

        // accessors: the bean is a CGLIB proxy (transactional listener), its own fields are not the target's
        public Map<UUID, List<Seen>> calls() {
            return calls;
        }

        public Set<UUID> failOnce() {
            return failOnce;
        }

        public Map<UUID, List<Seen>> onceCalls() {
            return onceCalls;
        }

        @ApplicationModuleListener
        public void on(ProbeEvent event) {
            calls.computeIfAbsent(event.probeId(), k -> new CopyOnWriteArrayList<>()).add(seen());
            if (failOnce.remove(event.probeId())) {
                throw new IllegalStateException("provider down (test)");
            }
        }

        @ApplicationModuleListener
        @DeliverOnce
        public void once(OnceProbeEvent event) {
            onceCalls.computeIfAbsent(event.probeId(), k -> new CopyOnWriteArrayList<>()).add(seen());
            if (failOnce.remove(event.probeId())) {
                throw new com.keevo.shared.event.DeliveryFailedException("provider down (test)",
                        new IllegalStateException("provider down (test)"));
            }
        }

        /** Tenant context + search_path of the connection bound to the listener's own transaction. */
        private Seen seen() {
            String searchPath = jdbc.queryForObject("SELECT current_setting('search_path')", String.class);
            return new Seen(TenantContext.getCurrentTenant(), searchPath, Thread.currentThread().getName());
        }
    }

    public static class ProbePublisher {
        private final ApplicationEventPublisher publisher;

        public ProbePublisher(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void publish(Object event) {
            publisher.publishEvent(event);
        }

        @Transactional
        public void publishThenFail(Object event) {
            publisher.publishEvent(event);
            throw new IllegalStateException("business error after publish (test)");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        ProbeListener eventRegistryProbeListener(DataSource dataSource) {
            return new ProbeListener(dataSource);
        }

        @Bean
        ProbePublisher eventRegistryProbePublisher(ApplicationEventPublisher publisher) {
            return new ProbePublisher(publisher);
        }
    }

    @Autowired private ProbeListener listener;
    @Autowired private ProbePublisher publisher;
    @Autowired private IncompleteEventPublications incomplete;
    @Autowired private DataSource dataSource;
    @Autowired private EventListenerReceipts receipts;

    private static final String ONCE_LISTENER_ID =
            ProbeListener.class.getName() + ".once(" + OnceProbeEvent.class.getName() + ")";

    private int onceReceipts() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM public.event_listener_receipt WHERE listener_id = ?",
                Integer.class, ONCE_LISTENER_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** Publication rows of one probe event: completion dates (null = incomplete). */
    private List<Map<String, Object>> publications(UUID probeId) {
        return jdbc().queryForList("SELECT listener_id, completion_date FROM public.event_publication "
                + "WHERE serialized_event LIKE ?", "%" + probeId + "%");
    }

    private void awaitCompleted(UUID probeId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(publications(probeId))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));
    }

    @Test
    @DisplayName("nominal: published in a tx → after commit the listener runs async in the event tenant → completed")
    void nominal() {
        ProbeEvent event = ProbeEvent.of("kv_evta01");

        publisher.publish(event);

        awaitCompleted(event.probeId());
        List<Seen> seen = listener.calls().get(event.probeId());
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).tenant()).isEqualTo("kv_evta01");
        assertThat(seen.get(0).searchPath()).contains("kv_evta01");
        assertThat(seen.get(0).thread()).startsWith("notification-");
        assertThat((String) publications(event.probeId()).get(0).get("listener_id"))
                .isEqualTo(ProbeListener.class.getName() + ".on(" + ProbeEvent.class.getName() + ")");
        assertThat(TenantContext.getCurrentTenant()).as("publisher thread untouched").isNull();
    }

    /** Publication rows of the real stock-alert listener for one product (Story 12.6a). */
    private List<Map<String, Object>> stockAlertPublications(UUID productId) {
        return jdbc().queryForList("SELECT listener_id, completion_date FROM public.event_publication "
                + "WHERE listener_id LIKE ? AND serialized_event LIKE ?",
                "%StockAlertNotificationListener.onStockThresholdBreached%", "%" + productId + "%");
    }

    private static com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent stockAlert(UUID productId) {
        return new com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent(productId, "Savon",
                UUID.randomUUID(), 2, 5, UUID.randomUUID(), "kv_evta01", Instant.now());
    }

    @Test
    @DisplayName("12.6a: stock alert is an after-commit module listener — commit → recorded + delivered; rollback → nothing")
    void stockAlertListener_afterCommitOnly() throws InterruptedException {
        UUID committed = UUID.randomUUID();
        publisher.publish(stockAlert(committed));
        await().atMost(WAIT).untilAsserted(() -> assertThat(stockAlertPublications(committed))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));

        UUID rolledBack = UUID.randomUUID();
        assertThatThrownBy(() -> publisher.publishThenFail(stockAlert(rolledBack)))
                .isInstanceOf(IllegalStateException.class);
        TimeUnit.MILLISECONDS.sleep(1500);
        // no publication row = the listener was never scheduled (before 12.6a it ran @Async before commit)
        assertThat(stockAlertPublications(rolledBack)).isEmpty();
    }

    @Test
    @DisplayName("12.6a: CSV import completion → onCsvImportCompleted publication recorded and completed")
    void csvImportCompletedListener_recordedAndCompleted() {
        UUID actor = UUID.randomUUID();
        publisher.publish(new com.keevo.shared.event.catalog.product.CsvImportCompletedEvent(
                3, "kv_evta01", actor, "Awa", "EMPLOYEE", Instant.now()));

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc().queryForList(
                "SELECT completion_date FROM public.event_publication WHERE listener_id LIKE ? AND serialized_event LIKE ?",
                "%ProductCreationNotificationListener.onCsvImportCompleted%", "%" + actor + "%"))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));
    }

    @Test
    @DisplayName("rollback: business error after publish → no publication row, no listener call")
    void rollback() throws InterruptedException {
        ProbeEvent event = ProbeEvent.of("kv_evta01");

        assertThatThrownBy(() -> publisher.publishThenFail(event)).isInstanceOf(IllegalStateException.class);

        TimeUnit.MILLISECONDS.sleep(1500);
        assertThat(publications(event.probeId())).isEmpty();
        assertThat(listener.calls()).doesNotContainKey(event.probeId());
    }

    @Test
    @DisplayName("listener fails → publication stays incomplete → resubmission invokes it once more → completed")
    void failureThenResubmit() throws InterruptedException {
        ProbeEvent event = ProbeEvent.of("kv_evtb02");
        listener.failOnce().add(event.probeId());

        publisher.publish(event);

        await().atMost(WAIT).until(() -> listener.calls().getOrDefault(event.probeId(), List.of()).size() == 1);
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(publications(event.probeId())).hasSize(1)
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNull());

        incomplete.resubmitIncompletePublications(p -> p.getEvent() instanceof ProbeEvent e
                && e.probeId().equals(event.probeId()));

        awaitCompleted(event.probeId());
        TimeUnit.MILLISECONDS.sleep(500);
        List<Seen> seen = listener.calls().get(event.probeId());
        assertThat(seen).as("first failing call + exactly one replay").hasSize(2);
        assertThat(seen).extracting(Seen::tenant).containsOnly("kv_evtb02");
    }

    @Test
    @DisplayName("two tenants publish concurrently → each listener runs in its own tenant schema")
    void twoTenantsConcurrently() throws Exception {
        int perTenant = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, List<UUID>> ids = new ConcurrentHashMap<>();
        try {
            List<Future<?>> runs = new CopyOnWriteArrayList<>();
            for (String tenant : List.of("kv_evtc03", "kv_evtd04")) {
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perTenant; i++) {
                        ProbeEvent event = ProbeEvent.of(tenant);
                        ids.computeIfAbsent(tenant, k -> new CopyOnWriteArrayList<>()).add(event.probeId());
                        // the publisher's own thread may carry another tenant: it must not leak
                        TenantContext.setCurrentTenant(tenant.equals("kv_evtc03") ? "kv_evtd04" : "kv_evtc03");
                        try {
                            publisher.publish(event);
                        } finally {
                            TenantContext.clear();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ids.forEach((tenant, probeIds) -> probeIds.forEach(id -> {
            awaitCompleted(id);
            List<Seen> seen = listener.calls().get(id);
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).tenant()).isEqualTo(tenant);
            assertThat(seen.get(0).searchPath()).contains(tenant);
        }));
    }

    @Test
    @DisplayName("@DeliverOnce: a publication replayed after delivery (crash before completion) is not delivered again")
    void deliverOnceSkipsReplay() throws InterruptedException {
        OnceProbeEvent event = OnceProbeEvent.of("kv_evte05");

        publisher.publish(event);
        awaitCompleted(event.probeId());
        assertThat(listener.onceCalls().get(event.probeId())).hasSize(1);

        // simulate a crash between the send and the registry's completion update
        jdbc().update("UPDATE public.event_publication SET completion_date = NULL WHERE serialized_event LIKE ?",
                "%" + event.probeId() + "%");
        incomplete.resubmitIncompletePublications(p -> p.getEvent() instanceof OnceProbeEvent e
                && e.probeId().equals(event.probeId()));

        awaitCompleted(event.probeId());
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(listener.onceCalls().get(event.probeId())).as("not delivered twice").hasSize(1);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM public.event_listener_receipt WHERE listener_id = ?",
                Integer.class, ProbeListener.class.getName() + ".once(" + OnceProbeEvent.class.getName() + ")"))
                .isPositive();
    }

    @Test
    @DisplayName("@DeliverOnce send failure → no receipt left, publication incomplete → resubmission delivers once")
    void deliverOnceFailureIsRetried() throws InterruptedException {
        OnceProbeEvent event = OnceProbeEvent.of("kv_evtf06");
        listener.failOnce().add(event.probeId());
        int receiptsBefore = onceReceipts();

        publisher.publish(event);

        await().atMost(WAIT).until(() -> listener.onceCalls().getOrDefault(event.probeId(), List.of()).size() == 1);
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(publications(event.probeId())).hasSize(1)
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNull());
        assertThat(onceReceipts()).as("receipt released after the failed send").isEqualTo(receiptsBefore);

        incomplete.resubmitIncompletePublications(p -> p.getEvent() instanceof OnceProbeEvent e
                && e.probeId().equals(event.probeId()));

        awaitCompleted(event.probeId());
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(listener.onceCalls().get(event.probeId())).as("failed attempt + one delivery").hasSize(2);
        assertThat(onceReceipts()).isEqualTo(receiptsBefore + 1);
    }

    @Test
    @DisplayName("EventListenerReceipts.purgeOlderThan deletes only receipts older than the retention")
    void receiptPurge() {
        String listenerId = "probe.purge(" + UUID.randomUUID() + ")";
        assertThat(receipts.tryAcquire(listenerId, "a".repeat(64))).isTrue();
        assertThat(receipts.tryAcquire(listenerId, "b".repeat(64))).isTrue();
        assertThat(receipts.tryAcquire(listenerId, "b".repeat(64))).as("second acquire refused").isFalse();
        jdbc().update("UPDATE public.event_listener_receipt SET received_at = NOW() - INTERVAL '31 days' "
                + "WHERE listener_id = ? AND event_hash = ?", listenerId, "a".repeat(64));

        int deleted = receipts.purgeOlderThan(EventListenerReceipts.RETENTION);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(jdbc().queryForList("SELECT event_hash FROM public.event_listener_receipt WHERE listener_id = ?",
                String.class, listenerId)).containsExactly("b".repeat(64));
    }
}
