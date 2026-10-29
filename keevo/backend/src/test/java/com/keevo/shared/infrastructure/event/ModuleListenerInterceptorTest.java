package com.keevo.shared.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.transfer.TransferCreatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Story 12.4 — tenant binding / restoring and {@code @DeliverOnce} receipts around module listeners. */
class ModuleListenerInterceptorTest {

    private static final Instant AT = Instant.parse("2026-10-29T10:00:00Z");

    @AfterEach
    void clear() {
        TenantContext.clear();
        MDC.clear();
    }

    /** Records the tenant seen inside each invocation. */
    public static class RecordingListener {
        final List<String> tenants = Collections.synchronizedList(new ArrayList<>());
        final List<String> mdcTenants = Collections.synchronizedList(new ArrayList<>());
        int deliveries;
        boolean fail;

        @ApplicationModuleListener
        public void on(TransferCreatedEvent event) {
            record();
            if (fail) {
                throw new IllegalStateException("provider down");
            }
        }

        @ApplicationModuleListener
        public void onPlan(PlanActivatedEvent event) {
            record();
        }

        @ApplicationModuleListener
        public void onObject(Object event) {
            record();
        }

        @ApplicationModuleListener
        @DeliverOnce
        public void once(TransferCreatedEvent event) {
            deliveries++;
            if (fail) {
                throw new IllegalStateException("provider down");
            }
        }

        @org.springframework.transaction.event.TransactionalEventListener
        @DeliverOnce
        public void syncOnce(TransferCreatedEvent event) {
            deliveries++;
            record();
            if (fail) {
                throw new IllegalStateException("provider down");
            }
        }

        private void record() {
            tenants.add(TenantContext.getCurrentTenant());
            mdcTenants.add(MDC.get("tenantId"));
        }
    }

    private static TransferCreatedEvent transfer(String tenant) {
        return new TransferCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, UUID.randomUUID(), tenant, AT);
    }

    /** Receipts backed by an in-memory set. */
    private static EventListenerReceipts inMemoryReceipts(Set<String> taken) {
        EventListenerReceipts receipts = mock(EventListenerReceipts.class);
        when(receipts.tryAcquire(anyString(), anyString()))
                .thenAnswer(inv -> taken.add(inv.getArgument(0) + "|" + inv.getArgument(1)));
        org.mockito.Mockito.doAnswer(inv -> taken.remove(inv.getArgument(0) + "|" + inv.getArgument(1)))
                .when(receipts).release(anyString(), anyString());
        return receipts;
    }

    private static RecordingListener bound(RecordingListener target, Set<String> receipts) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EventListenerReceipts store = inMemoryReceipts(receipts);
        return ModuleListenerTestSupport.bound(target, new ModuleListenerInterceptor(() -> store, () -> mapper));
    }

    @Test
    @DisplayName("binds the event's tenant (context + MDC) and restores the caller's afterwards")
    void bindsAndRestores() {
        RecordingListener target = new RecordingListener();
        RecordingListener listener = bound(target, new HashSet<>());
        TenantContext.setCurrentTenant("kv_caller");
        MDC.put("tenantId", "kv_caller");

        listener.on(transfer("kv_event"));

        assertThat(target.tenants).containsExactly("kv_event");
        assertThat(target.mdcTenants).containsExactly("kv_event");
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_caller");
        assertThat(MDC.get("tenantId")).isEqualTo("kv_caller");
    }

    @Test
    @DisplayName("no caller tenant → the context is cleared again after the listener")
    void clearsWhenNoPreviousTenant() {
        RecordingListener target = new RecordingListener();
        bound(target, new HashSet<>()).on(transfer("kv_event"));

        assertThat(target.tenants).containsExactly("kv_event");
        assertThat(TenantContext.getCurrentTenant()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    @DisplayName("listener throws → exception propagates (publication stays incomplete), tenant still restored")
    void restoresOnException() {
        RecordingListener target = new RecordingListener();
        target.fail = true;
        RecordingListener listener = bound(target, new HashSet<>());
        TenantContext.setCurrentTenant("kv_caller");

        assertThatThrownBy(() -> listener.on(transfer("kv_event"))).hasMessage("provider down");
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_caller");
    }

    @Test
    @DisplayName("plan events bind their schema name, not the tenant UUID")
    void planEventUsesSchemaName() {
        RecordingListener target = new RecordingListener();
        bound(target, new HashSet<>()).onPlan(new PlanActivatedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "kv_plan01", PlanType.FREE, AT, 5000, AT));

        assertThat(target.tenants).containsExactly("kv_plan01");
    }

    @Test
    @DisplayName("an event without tenant runs with no tenant bound (caller's tenant not leaked)")
    void eventWithoutTenantClearsContext() {
        RecordingListener target = new RecordingListener();
        TenantContext.setCurrentTenant("kv_caller");

        bound(target, new HashSet<>()).onObject("not tenant scoped");

        assertThat(target.tenants).containsExactly((String) null);
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_caller");
    }

    @Test
    @DisplayName("two tenants concurrently → each invocation sees its own tenant")
    void twoTenantsConcurrently() throws Exception {
        RecordingListener target = new RecordingListener();
        RecordingListener listener = bound(target, new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> runs = new ArrayList<>();
            for (String tenant : List.of("kv_aaaa01", "kv_bbbb02")) {
                runs.add(pool.submit(() -> {
                    start.await();
                    List<String> seen = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        RecordingListener probe = new RecordingListener();
                        bound(probe, new HashSet<>()).on(transfer(tenant));
                        seen.addAll(probe.tenants);
                        listener.on(transfer(tenant));
                    }
                    return seen;
                }));
            }
            start.countDown();
            assertThat(runs.get(0).get(30, TimeUnit.SECONDS)).hasSize(200).containsOnly("kv_aaaa01");
            assertThat(runs.get(1).get(30, TimeUnit.SECONDS)).hasSize(200).containsOnly("kv_bbbb02");
            assertThat(target.tenants).hasSize(400).containsOnly("kv_aaaa01", "kv_bbbb02");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("@DeliverOnce: a replayed event is skipped; a failed delivery releases its receipt")
    void deliverOnce() {
        Set<String> receipts = new HashSet<>();
        RecordingListener target = new RecordingListener();
        RecordingListener listener = bound(target, receipts);
        TransferCreatedEvent event = transfer("kv_event");

        listener.once(event);
        listener.once(event);                                     // replay of the same publication
        listener.once(transfer("kv_event"));                       // another event
        assertThat(target.deliveries).isEqualTo(2);
        assertThat(receipts).hasSize(2);

        target.fail = true;
        TransferCreatedEvent failing = transfer("kv_event");
        assertThatThrownBy(() -> listener.once(failing)).hasMessage("provider down");
        assertThat(receipts).as("receipt released after failure").hasSize(2);
        target.fail = false;
        listener.once(failing);                                    // resubmission retries it
        assertThat(target.deliveries).isEqualTo(4);
    }

    @Test
    @DisplayName("the receipt listener id is the registry's listener_id")
    void listenerIdMatchesTheRegistry() throws Exception {
        assertThat(ModuleListenerInterceptor.listenerId(
                RecordingListener.class.getMethod("once", TransferCreatedEvent.class)))
                .isEqualTo(RecordingListener.class.getName() + ".once(" + TransferCreatedEvent.class.getName() + ")");
    }

    @Test
    @DisplayName("synchronous @DeliverOnce listener: receipts applied, tenant left to the listener (not bound)")
    void syncDeliverOnce() {
        Set<String> receipts = new HashSet<>();
        RecordingListener target = new RecordingListener();
        RecordingListener listener = bound(target, receipts);
        TenantContext.setCurrentTenant("kv_caller");
        TransferCreatedEvent event = transfer("kv_event");

        listener.syncOnce(event);
        listener.syncOnce(event);

        assertThat(target.deliveries).isEqualTo(1);
        assertThat(target.tenants).containsExactly("kv_caller");
        assertThat(receipts).hasSize(1);
    }

    @Test
    @DisplayName("a failing receipt release never hides the listener's exception (attached as suppressed)")
    void releaseFailureIsSuppressed() {
        EventListenerReceipts store = mock(EventListenerReceipts.class);
        when(store.tryAcquire(anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(store).release(anyString(), anyString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RecordingListener target = new RecordingListener();
        target.fail = true;
        RecordingListener listener = ModuleListenerTestSupport.bound(target,
                new ModuleListenerInterceptor(() -> store, () -> mapper));

        assertThatThrownBy(() -> listener.once(transfer("kv_event")))
                .hasMessage("provider down")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("db down"));
    }

    @Test
    @DisplayName("an event that cannot be hashed is delivered without de-duplication (never blocked forever)")
    void unhashableEventIsDelivered() {
        EventListenerReceipts store = mock(EventListenerReceipts.class);
        RecordingListener target = new RecordingListener();
        RecordingListener listener = ModuleListenerTestSupport.bound(target, new ModuleListenerInterceptor(
                () -> store, () -> { throw new IllegalStateException("no mapper"); }));

        listener.once(transfer("kv_event"));

        assertThat(target.deliveries).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(store);
    }
}
