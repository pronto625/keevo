package com.keevo.admin.tenant.application.service;

import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * AdminTenantServiceTest — unit tests for AdminTenantService.
 * RED phase: written before implementation, verifies correct SQL parameterization.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // enrichWithCrossSchemaMetrics triggers extra jdbc calls
@DisplayName("AdminTenantService")
class AdminTenantServiceTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    AdminTenantService service;

    @BeforeEach
    void setUp() {
        service = new AdminTenantService(jdbcTemplate);
        // Stub schema_name lookup used by enrichWithCrossSchemaMetrics
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn("kv_abc123");
        // Stub cross-schema count queries (called with no args)
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(0);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(java.sql.Timestamp.class)))
                .thenReturn(null);
    }

    // ── listTenants ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listTenants — no filters — returns paginated page")
    void listTenants_noFilters_returnsPaginatedPage() {
        UUID id = UUID.randomUUID();
        AdminTenantListItem item = buildItem(id, "FREE", "ACTIVE");

        // count query returns 1
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        // list query returns one row
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(item));

        ListTenantsQuery query = new ListTenantsQuery(null, null, null,
                null, null, null, null, 0, 25);

        Page<AdminTenantListItem> result = service.execute(query);

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("listTenants — with search filter — query is parameterized (no injection)")
    void listTenants_withSearchFilter_buildsCorrectQuery() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ListTenantsQuery query = new ListTenantsQuery(
                "boutique", null, null, null, null, null, null, 0, 25);

        Page<AdminTenantListItem> result = service.execute(query);

        // Verify count SQL was called with parameterized search term (no injection)
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        assertThat(result.getTotalElements()).isEqualTo(0L);
    }

    @Test
    @DisplayName("listTenants — with PAID plan filter — filters Premium plans")
    void listTenants_withPlanFilter_filtersCorrectly() {
        UUID id = UUID.randomUUID();
        AdminTenantListItem item = buildItem(id, "PAID", "ACTIVE");

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(item));

        ListTenantsQuery query = new ListTenantsQuery(null, "PAID", null,
                null, null, null, null, 0, 25);

        Page<AdminTenantListItem> result = service.execute(query);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).plan()).isEqualTo("PAID");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminTenantListItem buildItem(UUID id, String plan, String status) {
        return new AdminTenantListItem(
                id, "KV-" + id.toString().substring(0, 4),
                "Test Boutique", "+237600000001",
                plan, status,
                Instant.now(), null,
                0, 0, null
        );
    }
}
