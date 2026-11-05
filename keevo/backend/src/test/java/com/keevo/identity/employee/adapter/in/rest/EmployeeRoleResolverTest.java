package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.Role;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Story 14.11 (AC5/AC6) / 19.4 — role, phone and username enrichment of {@code EmployeeResponseDto}, in bulk.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeRoleResolverTest {

    @Mock UserMembershipRepository membershipRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;

    @InjectMocks EmployeeRoleResolver resolver;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentTenant("kv_test01");
        lenient().when(tenantRepository.findBySchemaName("kv_test01")).thenReturn(Optional.of(
                new Tenant(tenantId, "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.FREE, Instant.now())));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static User user(UUID id, String phone, String username) {
        return new User(id, phone, "hash", Role.EMPLOYEE, true, Instant.now(), 0, null,
                null, username, null, null, null, null);
    }

    private static UserTenantMembership membership(UUID userId, UUID tenant, String role) {
        return new UserTenantMembership(UUID.randomUUID(), userId, tenant, role, true, Instant.now());
    }

    @Test
    @DisplayName("several employees → 3 queries in all (tenant, users by ids, memberships), one entry per user")
    void bulkThreeQueries() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(userRepository.findAllByIds(anyCollection())).thenReturn(List.of(
                user(a, "+237690000001", null), user(b, null, "Awa.K"), user(c, "+237690000003", "google-ab12cd34")));
        when(membershipRepository.findByTenantIdAndUserIdIn(any(), anyCollection())).thenReturn(List.of(
                membership(a, tenantId, "OWNER"), membership(b, tenantId, "EMPLOYEE")));

        Map<UUID, EmployeeRoleResolver.Enrichment> result = resolver.resolveAll(List.of(a, b, c));

        assertThat(result).containsEntry(a, new EmployeeRoleResolver.Enrichment("OWNER", "+237690000001", null))
                .containsEntry(b, new EmployeeRoleResolver.Enrichment("EMPLOYEE", "", "Awa.K"))
                .containsEntry(c, new EmployeeRoleResolver.Enrichment("EMPLOYEE", "+237690000003", null, false)); // 19.5: no membership
        verify(tenantRepository, times(1)).findBySchemaName("kv_test01");
        verify(userRepository, times(1)).findAllByIds(List.of(a, b, c));
        verify(membershipRepository, times(1)).findByTenantIdAndUserIdIn(tenantId, List.of(a, b, c));
        verify(userRepository, never()).findById(any());
        verify(membershipRepository, never()).findByUserIdAndTenantId(any(), any());
    }

    @Test
    @DisplayName("19.1 — known user without phone: \"\" (never null for an existing account)")
    void knownUserWithoutPhone_emptyPhone() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findAllByIds(anyCollection())).thenReturn(List.of(user(userId, null, "awa.n")));
        when(membershipRepository.findByTenantIdAndUserIdIn(any(), anyCollection())).thenReturn(List.of());

        EmployeeRoleResolver.Enrichment e = resolver.resolveAll(List.of(userId)).get(userId);

        assertThat(e.phoneNumber()).isEqualTo("");
        assertThat(e.username()).isEqualTo("awa.n");
    }

    @Test
    @DisplayName("unknown user → phone and username null, role EMPLOYEE")
    void unknownUser() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findAllByIds(anyCollection())).thenReturn(List.of());
        when(membershipRepository.findByTenantIdAndUserIdIn(any(), anyCollection())).thenReturn(List.of());

        assertThat(resolver.resolveAll(List.of(userId)).get(userId))
                .isEqualTo(new EmployeeRoleResolver.Enrichment("EMPLOYEE", null, null, false));
    }

    @Test
    @DisplayName("no employee → no query")
    void empty() {
        assertThat(resolver.resolveAll(List.of())).isEmpty();
        verifyNoInteractions(tenantRepository, userRepository, membershipRepository);
    }
}
