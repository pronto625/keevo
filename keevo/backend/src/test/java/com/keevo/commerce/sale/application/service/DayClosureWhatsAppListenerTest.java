package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * v1s-14-16 — the legacy day-closure report only ever goes to the closing tenant's owner.
 */
@ExtendWith(MockitoExtension.class)
class DayClosureWhatsAppListenerTest {

    private static final String TENANT = "kv_a";
    private static final String OWNER_PHONE = "+237600000001";
    private static final UUID STORE_ID = UUID.randomUUID();

    @Mock private WhatsAppPort whatsAppPort;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;

    private DayClosureWhatsAppListener listener;

    @BeforeEach
    void setUp() {
        listener = new DayClosureWhatsAppListener(whatsAppPort, storeRepository, userRepository);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static DayClosedEvent event(String tenantId) {
        return event(tenantId, false);
    }

    private static DayClosedEvent event(String tenantId, boolean automatic) {
        Instant now = Instant.parse("2026-10-11T18:00:00Z");
        var summary = new DayClosureSummary(5, 100000, null, null, 0, 60000, 40000, 0, 0);
        return new DayClosedEvent(UUID.randomUUID(), STORE_ID, UUID.randomUUID(), summary,
                automatic, tenantId, now, now.minusSeconds(36000), now);
    }

    private User ownerWithPhone(String phone) {
        User owner = mock(User.class);
        when(owner.getPhoneNumber()).thenReturn(phone);
        return owner;
    }

    private void storeNamed(String name) {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(
                new Store(STORE_ID, name, StoreType.STORE, null, null, true, Instant.now(), Instant.now())));
    }

    @Test
    void sendsToTenantOwnerPhone_withStoreNameInReport() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        storeNamed("Boutique Akwa");

        listener.onDayClosed(event(TENANT));

        ArgumentCaptor<String> report = ArgumentCaptor.forClass(String.class);
        verify(whatsAppPort, times(1)).sendReport(eq(OWNER_PHONE), report.capture());
        verifyNoMoreInteractions(whatsAppPort);
        assertThat(report.getValue()).contains("Boutique Akwa");
    }

    @Test
    void noOwner_skipsSend() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.empty());

        listener.onDayClosed(event(TENANT));

        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void ownerPhoneNull_skipsSend() {
        User owner = ownerWithPhone(null);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));

        listener.onDayClosed(event(TENANT));

        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void ownerPhoneBlank_skipsSend() {
        User owner = ownerWithPhone("   ");
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));

        listener.onDayClosed(event(TENANT));

        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void nullTenant_noLookupNoSend() {
        listener.onDayClosed(event(null));

        verifyNoInteractions(userRepository, whatsAppPort);
    }

    @Test
    void blankTenant_noLookupNoSend() {
        listener.onDayClosed(event("  "));

        verifyNoInteractions(userRepository, whatsAppPort);
    }

    @Test
    void systemPropertyIsIgnored_sendsToOwnerOnly() {
        String previous = System.getProperty("keevo.owner.whatsapp");
        System.setProperty("keevo.owner.whatsapp", "+243000000000");
        try {
            User owner = ownerWithPhone(OWNER_PHONE);
            when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
            storeNamed("Boutique Akwa");

            listener.onDayClosed(event(TENANT));

            verify(whatsAppPort).sendReport(eq(OWNER_PHONE), anyString());
            verify(whatsAppPort, never()).sendReport(eq("+243000000000"), anyString());
        } finally {
            if (previous == null) {
                System.clearProperty("keevo.owner.whatsapp");
            } else {
                System.setProperty("keevo.owner.whatsapp", previous);
            }
        }
    }

    @Test
    void lookupThrows_noSendNoException() {
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> listener.onDayClosed(event(TENANT)));

        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void sendReportThrows_noExceptionEscapes() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        storeNamed("Boutique Akwa");
        doThrow(new RuntimeException("wa down")).when(whatsAppPort).sendReport(anyString(), anyString());

        assertDoesNotThrow(() -> listener.onDayClosed(event(TENANT)));
    }

    @Test
    void storeLookupThrows_noExceptionEscapes_nothingSent() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        when(storeRepository.findById(any())).thenThrow(new RuntimeException("store db down"));

        assertDoesNotThrow(() -> listener.onDayClosed(event(TENANT)));

        verifyNoInteractions(whatsAppPort);
    }

    @Test
    void listenerRunsAfterCommit() throws NoSuchMethodException {
        Method m = DayClosureWhatsAppListener.class.getMethod("onDayClosed", DayClosedEvent.class);
        TransactionalEventListener ann = m.getAnnotation(TransactionalEventListener.class);

        assertThat(ann).isNotNull();
        assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void storeLookupRunsInEventTenant_previousContextRestored() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        AtomicReference<String> tenantDuringLookup = new AtomicReference<>();
        when(storeRepository.findById(STORE_ID)).thenAnswer(inv -> {
            tenantDuringLookup.set(TenantContext.getCurrentTenant());
            return Optional.empty();
        });
        TenantContext.setCurrentTenant("kv_other");

        listener.onDayClosed(event(TENANT));

        assertThat(tenantDuringLookup.get()).isEqualTo(TENANT);
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_other");
    }

    @Test
    void storeLookupRunsInEventTenant_contextClearedWhenNonePreviously() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        AtomicReference<String> tenantDuringLookup = new AtomicReference<>();
        when(storeRepository.findById(STORE_ID)).thenAnswer(inv -> {
            tenantDuringLookup.set(TenantContext.getCurrentTenant());
            return Optional.empty();
        });
        TenantContext.clear();

        listener.onDayClosed(event(TENANT));

        assertThat(tenantDuringLookup.get()).isEqualTo(TENANT);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    void automaticClosure_sendsToOwner() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        storeNamed("Boutique Akwa");

        listener.onDayClosed(event(TENANT, true));

        ArgumentCaptor<String> report = ArgumentCaptor.forClass(String.class);
        verify(whatsAppPort).sendReport(eq(OWNER_PHONE), report.capture());
        assertThat(report.getValue()).contains("Boutique Akwa");
    }

    @Test
    void storeNotFound_sendsWithFallbackName() {
        User owner = ownerWithPhone(OWNER_PHONE);
        when(userRepository.findOwnerByTenantSchemaName(TENANT)).thenReturn(Optional.of(owner));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.empty());

        listener.onDayClosed(event(TENANT));

        ArgumentCaptor<String> report = ArgumentCaptor.forClass(String.class);
        verify(whatsAppPort).sendReport(eq(OWNER_PHONE), report.capture());
        assertThat(report.getValue()).contains("Boutique #" + STORE_ID.toString().substring(0, 8));
    }
}
