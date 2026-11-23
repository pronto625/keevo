package com.keevo.identity.legacyimport.application;

import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.identity.legacyimport.application.port.LegacyIdentityStore;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("LegacyIdentityImportService.createOwnerTenant — Story 22.19 (D1)")
class LegacyIdentityImportServiceSetupTest {

    @Test
    @DisplayName("an imported company has its sign-up steps marked completed (no sign-up again after the « go »)")
    void importedCompanySetupCompleted() {
        LegacyIdentityStore store = mock(LegacyIdentityStore.class);
        TenantFactory factory = mock(TenantFactory.class);
        Instant now = Instant.parse("2026-11-03T10:00:00Z");
        UUID ownerId = UUID.randomUUID();
        Tenant tenant = new Tenant(UUID.randomUUID(), "KV-IMP001", "kv_imp001", TenantStatus.SUSPENDED,
                PlanType.FREE, now);
        when(factory.create(any(TenantFactory.TenantCreation.class))).thenReturn(tenant);
        when(store.insertAccounts(anyList(), anyList(), anyList())).thenReturn(Set.of(ownerId));
        when(store.insertMemberships(any(), anyList(), anyList(), anyList())).thenReturn(Map.of(ownerId,
                UUID.randomUUID()));
        var service = new LegacyIdentityImportService(store, factory, mock(TenantSchemaPort.class),
                mock(TenantSchemaProvisioner.class));

        service.createOwnerTenant(new LegacyAccount(ownerId, "+237690000001", null, null, null, "Aicha", "Mbarga",
                null, now, now, now, null, null), null, "Boutique Aicha");

        verify(store).markSetupCompleted(tenant.getId());
    }
}
