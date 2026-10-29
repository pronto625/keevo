package com.keevo.messaging.notification.application.listener;

import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.commerce.sale.domain.event.DayClosedEvent;
import com.keevo.commerce.api.DayClosureSummary;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** J21 (device test): « Clôture automatique — 0 vente(s) · 0 FCFA » pushed for a warehouse. */
class DayClosureNotificationListenerTest {

    private static final String TENANT = "kv_test";
    private static final UUID STORE_ID = UUID.randomUUID();

    private NotificationPort notificationPort;
    private StoreRepository storeRepository;
    private DayClosureNotificationListener listener;

    @BeforeEach
    void setUp() {
        notificationPort = mock(NotificationPort.class);
        storeRepository = mock(StoreRepository.class);
        listener = new DayClosureNotificationListener(notificationPort, StoreApiTestSupport.queryPort(storeRepository));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DayClosedEvent event(boolean automatic, int sales, int revenue) {
        return new DayClosedEvent(UUID.randomUUID(), STORE_ID, UUID.randomUUID(),
                new DayClosureSummary(sales, revenue, null, null, 0, revenue, 0, 0, 0),
                automatic, TENANT, Instant.now(), Instant.now(), Instant.now());
    }

    private void storeIs(String name, StoreType type) {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(
                new Store(STORE_ID, name, type, null, null, true, Instant.now(), Instant.now())));
    }

    private NotificationPayload pushed() {
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT), captor.capture());
        return captor.getValue();
    }

    @Test
    void body_namesTheStore_andUsesXaf() {
        storeIs("Boutique Akwa", StoreType.STORE);

        listener.onDayClosed(event(false, 3, 4400));

        String body = pushed().body();
        assertThat(body).startsWith("Boutique Akwa — Clôture manuelle : 3 vente(s) · 4 400 XAF");
        assertThat(body).doesNotContain("FCFA");
        assertThat(pushed().metadata()).containsEntry("storeName", "Boutique Akwa");
    }

    @Test
    void automaticClosureWithSales_isPushed() {
        storeIs("Boutique Akwa", StoreType.STORE);

        listener.onDayClosed(event(true, 2, 1000));

        assertThat(pushed().body()).contains("Clôture automatique : 2 vente(s)");
    }

    @Test
    void automaticClosureWithoutSales_isNotPushed() {
        storeIs("Boutique Akwa", StoreType.STORE);

        listener.onDayClosed(event(true, 0, 0));

        verify(notificationPort, never()).notifyOwners(anyString(), any());
    }

    @Test
    void warehouseClosure_isNotPushed() {
        storeIs("Dépôt Bonaberi", StoreType.WAREHOUSE);

        listener.onDayClosed(event(false, 1, 500));

        verify(notificationPort, never()).notifyOwners(anyString(), any());
    }

    @Test
    void unknownStore_fallsBackToGenericName_andRestoresNoTenant() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.empty());

        listener.onDayClosed(event(false, 1, 500));

        assertThat(pushed().body()).startsWith("Boutique — Clôture manuelle");
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }
}
