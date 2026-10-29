package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.Role;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Story 12.2d — tenant / user adapters of {@code identity :: api} and the tenant guard. */
@ExtendWith(MockitoExtension.class)
class IdentityAuthApiAdaptersTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;

    @Test
    void tenantQuery_mapsEveryField() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant deletion = Instant.parse("2026-11-01T00:00:00Z");
        Tenant t = new Tenant(UUID.randomUUID(), "KV-ABC123", "kv_abc123", "Boutique Simon",
                TenantStatus.DELETION_PENDING, PlanType.PREMIUM_TRIAL, created, deletion);
        when(tenantRepository.findAll()).thenReturn(List.of(t));
        when(tenantRepository.findBySchemaName("kv_abc123")).thenReturn(Optional.of(t));
        var adapter = new TenantQueryAdapter(tenantRepository);

        TenantView expected = new TenantView(t.getId(), "KV-ABC123", "kv_abc123", "Boutique Simon",
                TenantStatus.DELETION_PENDING, PlanType.PREMIUM_TRIAL, created, deletion);
        assertThat(adapter.findAll()).containsExactly(expected);
        assertThat(adapter.findBySchemaName("kv_abc123")).contains(expected);
        assertThat(expected.isActive()).isFalse();
    }

    @Test
    void userQuery_phoneAndOwnerIds() {
        UUID owner = UUID.randomUUID();
        User u = new User(owner, "+237690000001", "hash", Role.OWNER, true, Instant.now());
        when(userRepository.findById(owner)).thenReturn(Optional.of(u));
        when(userRepository.findOwnersByTenantSchemaName("kv_abc123")).thenReturn(List.of(u));
        var adapter = new UserQueryAdapter(userRepository);

        assertThat(adapter.findPhoneNumber(owner)).contains("+237690000001");
        assertThat(adapter.findPhoneNumber(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findOwnerUserIds("kv_abc123")).containsExactly(owner);
    }
}
