package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.PlanChangeCause;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.PlanChangeRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChangeTenantPlanService (Story 9-2)")
class ChangeTenantPlanServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SCHEMA = "kv_abc123";

    @Mock JdbcTemplate jdbc;
    @Mock PlanChangeRecorder recorder;
    @InjectMocks ChangeTenantPlanService service;

    @SuppressWarnings("unchecked")
    private void currentRow(UUID rowId, PlanType plan) throws Exception {
        when(jdbc.query(contains("\"kv_abc123\".subscriptions"), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("id")).thenReturn(rowId.toString());
            when(rs.getString("plan_type")).thenReturn(plan.name());
            when(rs.getTimestamp("expires_at")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @Test
    @DisplayName("PREMIUM: subscription row updated with Premium limits + expiry, public plan synced, event recorded")
    void changeToPremium() throws Exception {
        UUID rowId = UUID.randomUUID();
        currentRow(rowId, PlanType.FREE);
        Instant expires = Instant.parse("2026-11-12T22:59:59Z");

        CurrentPlan previous = service.changePlan(new ChangeTenantPlanCommand(TENANT, SCHEMA, PlanType.PREMIUM,
                expires, PlanChangeCause.ADMIN_ACTIVATION, ACTOR, "note"));

        assertThat(previous.planType()).isEqualTo(PlanType.FREE);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("PREMIUM"), eq(Integer.MAX_VALUE), eq(Integer.MAX_VALUE),
                eq(Integer.MAX_VALUE), eq(Timestamp.from(expires)), eq(rowId));
        assertThat(sql.getValue()).contains("\"kv_abc123\".subscriptions").contains("status = 'ACTIVE'");
        verify(recorder).syncPublicPlanType(TENANT, PlanType.PREMIUM);
        verify(recorder).recordEvent(TENANT, PlanType.FREE, PlanType.PREMIUM, PlanChangeCause.ADMIN_ACTIVATION, ACTOR, "note");
    }

    @Test
    @DisplayName("FREE: FREE limits and no expiry")
    void changeToFree() throws Exception {
        UUID rowId = UUID.randomUUID();
        currentRow(rowId, PlanType.PREMIUM);

        service.changePlan(new ChangeTenantPlanCommand(TENANT, SCHEMA, PlanType.FREE, null,
                PlanChangeCause.ADMIN_DOWNGRADE, ACTOR, null));

        verify(jdbc).update(anyString(), eq("FREE"), eq(1), eq(500), eq(3), isNull(), eq(rowId));
        verify(recorder).syncPublicPlanType(TENANT, PlanType.FREE);
    }

    @Test
    @DisplayName("no subscription row → SUBSCRIPTION_NOT_FOUND, nothing written")
    @SuppressWarnings("unchecked")
    void noRow() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.changePlan(new ChangeTenantPlanCommand(TENANT, SCHEMA, PlanType.FREE, null,
                PlanChangeCause.ADMIN_DOWNGRADE, ACTOR, null)))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("invalid schema name is never interpolated")
    void invalidSchema() {
        assertThatThrownBy(() -> service.currentPlan("kv_x\"; DROP")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
