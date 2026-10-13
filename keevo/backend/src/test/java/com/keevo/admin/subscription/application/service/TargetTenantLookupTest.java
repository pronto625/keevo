package com.keevo.admin.subscription.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TargetTenantLookup (Story 9-2)")
class TargetTenantLookupTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks TargetTenantLookup lookup;

    @Test
    @DisplayName("unknown id or system tenant → TENANT_NOT_FOUND (SQL excludes KV-ADMIN)")
    @SuppressWarnings("unchecked")
    void unknown() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(eq(TargetTenantLookup.SELECT_SQL), any(RowMapper.class), eq(id))).thenReturn(List.of());

        assertThatThrownBy(() -> lookup.load(id))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        assertThat(TargetTenantLookup.SELECT_SQL).contains("00000000-0000-0000-0000-000000000000");
    }

    @Test
    @DisplayName("invalid stored schema name → TENANT_NOT_FOUND")
    @SuppressWarnings("unchecked")
    void invalidSchema() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(eq(TargetTenantLookup.SELECT_SQL), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new TargetTenantLookup.TargetTenant(id, "x", "kv_x\"; DROP", "ACTIVE")));

        assertThatThrownBy(() -> lookup.load(id))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
    }
}
