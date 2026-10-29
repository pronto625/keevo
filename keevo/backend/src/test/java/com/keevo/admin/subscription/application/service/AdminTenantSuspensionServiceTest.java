package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.application.service.TargetTenantLookup.TargetTenant;
import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantCommand;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminTenantSuspensionService (Story 9-2)")
class AdminTenantSuspensionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String SCHEMA = "kv_abc123";

    @Mock TargetTenantLookup lookup;
    @Mock JdbcTemplate jdbc;
    @Mock TokenRevocationPort revocation;
    @Mock ApplicationEventPublisher publisher;

    AdminTenantSuspensionService service;

    @BeforeEach
    void setUp() {
        service = new AdminTenantSuspensionService(lookup, jdbc, revocation, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void tenant(String status) {
        when(lookup.load(TENANT)).thenReturn(new TargetTenant(TENANT, "Boutique", SCHEMA, status));
    }

    @SuppressWarnings("unchecked")
    private void members(UUID... ids) {
        when(jdbc.query(eq(AdminTenantSuspensionService.MEMBERS_SQL), any(RowMapper.class), eq(TENANT)))
                .thenReturn(List.of(ids));
    }

    @Test
    @DisplayName("suspend ACTIVE: status SUSPENDED, every member session revoked, event published")
    void suspend_active() {
        tenant("ACTIVE");
        when(jdbc.update(AdminTenantSuspensionService.SUSPEND_SQL, java.sql.Timestamp.from(NOW), "fraude", ACTOR, TENANT))
                .thenReturn(1);
        UUID owner = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        members(owner, employee);

        service.suspend(new SuspendTenantCommand(ACTOR, TENANT, " fraude "));

        verify(revocation).revokeAllSessions(owner, SCHEMA);
        verify(revocation).revokeAllSessions(employee, SCHEMA);
        verify(publisher).publishEvent(new TenantSuspendedEvent(ACTOR, TENANT, SCHEMA, "fraude", NOW));
    }

    @Test
    @DisplayName("suspend DELETION_PENDING (conditional UPDATE matches nothing) → 409 INVALID_STATE, no revocation")
    void suspend_deletionPending() {
        tenant("DELETION_PENDING");
        when(jdbc.update(AdminTenantSuspensionService.SUSPEND_SQL, java.sql.Timestamp.from(NOW), "fraude", ACTOR, TENANT))
                .thenReturn(0);

        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(ACTOR, TENANT, "fraude")))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        verifyNoInteractions(revocation, publisher);
    }

    @Test
    @DisplayName("suspend without a usable reason (null, blank, < 3 chars, > 500) → VALIDATION_ERROR, nothing touched")
    void suspend_reasonRequired() {
        for (String reason : new String[]{null, "   ", "ab", "x".repeat(501)}) {
            assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(ACTOR, TENANT, reason)))
                    .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("VALIDATION_ERROR");
        }
        verifyNoInteractions(lookup, jdbc, revocation, publisher);
    }

    @Test
    @DisplayName("suspend SQL only moves an ACTIVE tenant")
    void suspendSql_isConditional() {
        assertThat(AdminTenantSuspensionService.SUSPEND_SQL).contains("status = 'SUSPENDED'").contains("AND status = 'ACTIVE'");
        assertThat(AdminTenantSuspensionService.REACTIVATE_SQL).contains("status = 'ACTIVE'").contains("AND status = 'SUSPENDED'");
        // suspension trace written on suspend, cleared on reactivation
        assertThat(AdminTenantSuspensionService.SUSPEND_SQL)
                .contains("suspended_at = ?").contains("suspended_reason = ?").contains("suspended_by = ?");
        assertThat(AdminTenantSuspensionService.REACTIVATE_SQL)
                .contains("suspended_at = NULL").contains("suspended_reason = NULL").contains("suspended_by = NULL");
    }

    @Test
    @DisplayName("reactivate SUSPENDED: status ACTIVE, sessions revoked, event published")
    void reactivate_suspended() {
        tenant("SUSPENDED");
        when(jdbc.update(AdminTenantSuspensionService.REACTIVATE_SQL, TENANT)).thenReturn(1);
        UUID owner = UUID.randomUUID();
        members(owner);

        service.reactivate(ACTOR, TENANT);

        verify(revocation).revokeAllSessions(owner, SCHEMA);
        verify(publisher).publishEvent(new TenantReactivatedEvent(ACTOR, TENANT, SCHEMA, NOW));
    }

    @Test
    @DisplayName("reactivate a tenant that is not SUSPENDED → INVALID_STATE")
    void reactivate_notSuspended() {
        tenant("ACTIVE");
        when(jdbc.update(AdminTenantSuspensionService.REACTIVATE_SQL, TENANT)).thenReturn(0);

        assertThatThrownBy(() -> service.reactivate(ACTOR, TENANT))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        verifyNoInteractions(revocation, publisher);
    }

    @Test
    @DisplayName("unknown tenant → TENANT_NOT_FOUND")
    void unknownTenant() {
        when(lookup.load(TENANT)).thenThrow(new DomainException(ErrorCode.TENANT_NOT_FOUND, "x"));

        assertThatThrownBy(() -> service.reactivate(ACTOR, TENANT))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        verifyNoInteractions(jdbc, revocation);
    }
}
