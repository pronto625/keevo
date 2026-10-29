package com.keevo.inventory.counting.adapter.in.api;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Story 12.2b — the counting query port exposes a session's store and validated state. */
@ExtendWith(MockitoExtension.class)
class CountingQueryAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private InventorySessionRepository sessionRepository;

    private CountingQueryAdapter adapter;

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new CountingQueryAdapter(sessionRepository);
    }

    private static InventorySession session(InventorySessionStatus status) {
        return new InventorySession(SESSION, STORE, InventoryScope.FULL, null, status,
                UUID.randomUUID(), Instant.now(), null, null, null, Instant.now());
    }

    @Test
    void findSessionStoreId_existingSession() {
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.of(session(InventorySessionStatus.IN_PROGRESS)));

        assertThat(adapter.findSessionStoreId(TenantContext.getCurrentTenant(), SESSION)).contains(STORE);
    }

    @Test
    void findSessionStoreId_unknownSession_isEmpty() {
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.empty());

        assertThat(adapter.findSessionStoreId(TenantContext.getCurrentTenant(), SESSION)).isEmpty();
    }

    @Test
    void isSessionValidated_onlyForAValidatedSession() {
        when(sessionRepository.findById(SESSION))
                .thenReturn(Optional.of(session(InventorySessionStatus.VALIDATED)))
                .thenReturn(Optional.of(session(InventorySessionStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());

        assertThat(adapter.isSessionValidated(TenantContext.getCurrentTenant(), SESSION)).isTrue();
        assertThat(adapter.isSessionValidated(TenantContext.getCurrentTenant(), SESSION)).isFalse();
        assertThat(adapter.isSessionValidated(TenantContext.getCurrentTenant(), SESSION)).isFalse();
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findSessionStoreId("kv_other1", SESSION))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findSessionStoreId(null, SESSION))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(sessionRepository);
    }
}
