package com.keevo.admin.tenant.application.service;

import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
import org.springframework.jdbc.core.RowCallbackHandler;
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

    @Mock
    com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler accountDeletionScheduler;

    AdminTenantService service;

    @BeforeEach
    void setUp() {
        service = new AdminTenantService(jdbcTemplate, accountDeletionScheduler);
        // Stub the batched schema_name lookup (Story 11.5): every requested id → "kv_abc123"
        lenient().doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Object id : idArgs(inv.getArguments())) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("id")).thenReturn(id.toString());
                when(rs.getString("schema_name")).thenReturn("kv_abc123");
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));
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
    @DisplayName("12.12 — phone search matches the raw term and its '+<digits>' form")
    void listTenants_phoneSearch_matchesNormalisedPhone() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        service.execute(new ListTenantsQuery("237600000001", null, null, null, null, null, null, 0, 25));

        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.contains("owner.phone_number IN (?, ?)"), eq(Long.class),
                eq("%237600000001%"), eq("237600000001"), eq("+237600000001"));
    }

    @Test
    @DisplayName("listTenants — with PAID plan filter — filters Premium plans")
    void listTenants_withPlanFilter_filtersCorrectly() {
        UUID id = UUID.randomUUID();
        AdminTenantListItem item = buildItem(id, "PREMIUM", "ACTIVE");

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(item));

        ListTenantsQuery query = new ListTenantsQuery(null, "PAID", null,
                null, null, null, null, 0, 25);

        Page<AdminTenantListItem> result = service.execute(query);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).plan()).isEqualTo("PREMIUM");
    }

    @Test
    @DisplayName("11.5 — listTenants loads the schema names of the whole page in one query (no N+1)")
    void listTenants_multipleTenants_loadsSchemaNamesInOneQuery() {
        List<AdminTenantListItem> rows = List.of(
                buildItem(UUID.randomUUID(), "FREE", "ACTIVE"),
                buildItem(UUID.randomUUID(), "PREMIUM_TRIAL", "ACTIVE"),
                buildItem(UUID.randomUUID(), "FREE", "SUSPENDED"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
        when(jdbcTemplate.queryForObject(contains("kv_abc123.stores"), eq(Integer.class))).thenReturn(2);

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        verify(jdbcTemplate, times(1))
                .query(contains("WHERE id IN (?,?,?)"), any(RowCallbackHandler.class), any(Object[].class));
        verify(jdbcTemplate, never())
                .queryForObject(contains("schema_name"), eq(String.class), any(Object[].class));
        // content unchanged apart from the cross-schema metrics, in the same order
        assertThat(result.getContent()).extracting(AdminTenantListItem::id)
                .containsExactlyElementsOf(rows.stream().map(AdminTenantListItem::id).toList());
        assertThat(result.getContent()).extracting(AdminTenantListItem::storeCount).containsOnly(2);
    }

    @Test
    @DisplayName("11.5 — tenant missing from the batched lookup is kept with zero metrics")
    void listTenants_missingSchema_keepsItemWithZeroMetrics() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(id, "FREE", "ACTIVE")));
        doNothing().when(jdbcTemplate)
                .query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        assertThat(result.getContent()).singleElement()
                .satisfies(it -> {
                    assertThat(it.id()).isEqualTo(id);
                    assertThat(it.storeCount()).isZero();
                    assertThat(it.lastActivityAt()).isNull();
                });
        verify(jdbcTemplate, never()).queryForObject(contains(".stores"), eq(Integer.class));
    }

    @Test
    @DisplayName("11.5 — batched schema lookup fails → items kept with zero metrics, no cross-schema query")
    void listTenants_batchedLookupFails_keepsItemsWithZeroMetrics() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(a, "FREE", "ACTIVE"), buildItem(b, "PREMIUM", "ACTIVE")));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(jdbcTemplate)
                .query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        assertThat(result.getContent()).extracting(AdminTenantListItem::id).containsExactly(a, b);
        assertThat(result.getContent()).allSatisfy(it -> {
            assertThat(it.storeCount()).isZero();
            assertThat(it.lastActivityAt()).isNull();
        });
        verify(jdbcTemplate, never()).queryForObject(contains(".stores"), eq(Integer.class));
    }

    @Test
    @DisplayName("11.5 — tenant detail looks the schema name up exactly once and reuses it for the metrics")
    void tenantDetail_loadsSchemaNameOnce() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.query(contains("WHERE t.id = ?"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(id, "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForObject(contains("SELECT schema_name FROM public.tenants"), eq(String.class),
                any(Object[].class))).thenReturn("kv_abc123");
        when(jdbcTemplate.queryForObject(contains("kv_abc123.stores"), eq(Integer.class))).thenReturn(3);

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        verify(jdbcTemplate, times(1)).queryForObject(contains("schema_name"), eq(String.class), any(Object[].class));
        verify(jdbcTemplate, never())
                .query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));
        assertThat(detail.summary().id()).isEqualTo(id);
        assertThat(detail.summary().storeCount()).isEqualTo(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Flattens the varargs of a {@code query(sql, handler, Object...)} invocation. */
    private static List<Object> idArgs(Object[] args) {
        List<Object> out = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i] instanceof Object[] arr) out.addAll(List.of(arr));
            else out.add(args[i]);
        }
        return out;
    }

    private AdminTenantListItem buildItem(UUID id, String plan, String status) {
        return new AdminTenantListItem(
                id, "KV-" + id.toString().substring(0, 4),
                "Test Boutique", "+237600000001",
                plan, status,
                Instant.now(), null,
                0, 0, null
        );
    }

    // ── v1s-15-9: cancel / forceDelete ────────────────────────────────────────

    @Test
    @DisplayName("cancelDeletion — conditional UPDATE also refuses a dropped schema")
    void cancelDeletion_refusesDroppedSchema() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(1);

        service.cancelDeletion(id);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(id));
        assertThat(sql.getValue())
                .contains("status = 'DELETION_PENDING'")
                .contains("to_regnamespace(schema_name) IS NOT NULL");
    }

    @Test
    @DisplayName("cancelDeletion — 0 rows → TENANT_NOT_FOUND")
    void cancelDeletion_noRow_throws() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(0);

        assertThatThrownBy(() -> service.cancelDeletion(id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("TENANT_NOT_FOUND"));
    }

    @Test
    @DisplayName("forceDelete — commits a DELETION_PENDING marker then runs the scheduler routine")
    void forceDelete_marksPendingThenDeletesNow() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(1);
        when(accountDeletionScheduler.deleteNow(id)).thenReturn(true);

        service.forceDelete(id);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        var order = inOrder(jdbcTemplate, accountDeletionScheduler);
        order.verify(jdbcTemplate).update(sql.capture(), eq(id));
        order.verify(accountDeletionScheduler).deleteNow(id);
        assertThat(sql.getValue())
                .contains("status = 'DELETION_PENDING', deletion_scheduled_at = now()")
                .contains("status <> 'DELETED'")
                .contains("00000000-0000-0000-0000-000000000000")
                .doesNotContain("SET status = 'DELETED'");
    }

    @Test
    @DisplayName("forceDelete — unknown / DELETED / system tenant → TENANT_NOT_FOUND, routine never runs")
    void forceDelete_invalidTarget_throwsTenantNotFound() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(0);

        assertThatThrownBy(() -> service.forceDelete(id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("TENANT_NOT_FOUND"));
        verifyNoInteractions(accountDeletionScheduler);
    }

    @Test
    @DisplayName("forceDelete — routine skips and tenant is not DELETED (cancelled concurrently) → DELETION_NOT_PENDING")
    void forceDelete_routineSkipped_throwsDeletionNotPending() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(1);
        when(accountDeletionScheduler.deleteNow(id)).thenReturn(false);
        when(jdbcTemplate.queryForObject(startsWith("SELECT status FROM public.tenants"), eq(String.class), eq(id)))
                .thenReturn("ACTIVE");

        assertThatThrownBy(() -> service.forceDelete(id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("DELETION_NOT_PENDING"));
    }

    @Test
    @DisplayName("forceDelete — routine skips because a concurrent daily run already DELETED it → success")
    void forceDelete_concurrentlyDeletedByDailyRun_returnsNormally() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(id))).thenReturn(1);
        when(accountDeletionScheduler.deleteNow(id)).thenReturn(false);
        when(jdbcTemplate.queryForObject(startsWith("SELECT status FROM public.tenants"), eq(String.class), eq(id)))
                .thenReturn("DELETED");

        service.forceDelete(id);

        verify(accountDeletionScheduler).deleteNow(id);
    }
    // ── v1s-13-12 follow-up: invalid schema_name never interpolated ──────────

    @Test
    @DisplayName("getTenantDetail — invalid stored schema_name → TENANT_NOT_FOUND, no tenant switch, no schema SQL")
    @SuppressWarnings("unchecked")
    void getTenantDetail_invalidSchema_notFound() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(id)))
                .thenReturn(List.of(new AdminTenantListItem(id, "KV-TEST", "Test Boutique", "+237600000001",
                        "FREE", "ACTIVE", Instant.now(), null, 0, 0, null)));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn("kv_x\"; DROP");

        assertThatThrownBy(() -> service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        verify(jdbcTemplate, never()).queryForObject(contains("DROP"), eq(Integer.class));
        verify(jdbcTemplate, never()).queryForObject(contains("DROP"), eq(java.sql.Timestamp.class));
        assertThat(com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant()).isNull();
    }
}
