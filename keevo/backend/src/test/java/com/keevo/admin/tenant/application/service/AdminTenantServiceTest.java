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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        // Stub the single per-schema metrics query (spec 9-1 completion): 0 stores, no activity
        lenient().when(jdbcTemplate.queryForList(contains(".stores"), any(Object[].class)))
                .thenReturn(List.of(metrics(0, null, null)));
    }

    /** One row of the per-schema metrics query. */
    private static Map<String, Object> metrics(int stores, java.sql.Timestamp lastActivity, String ownerName) {
        Map<String, Object> row = new HashMap<>();
        row.put("store_count", (long) stores);
        row.put("last_activity", lastActivity);
        row.put("owner_name", ownerName);
        return row;
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
        when(jdbcTemplate.queryForList(contains("kv_abc123.stores"), any(Object[].class)))
                .thenReturn(List.of(metrics(2, null, null)));

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
        verify(jdbcTemplate, never()).queryForList(contains(".stores"), any(Object[].class));
    }

    @Test
    @DisplayName("11.5 — batched schema lookup SQL error → items kept with zero metrics, no cross-schema query")
    void listTenants_batchedLookupFails_keepsItemsWithZeroMetrics() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(a, "FREE", "ACTIVE"), buildItem(b, "PREMIUM", "ACTIVE")));
        doThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql", new java.sql.SQLException("bad")))
                .when(jdbcTemplate)
                .query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        assertThat(result.getContent()).extracting(AdminTenantListItem::id).containsExactly(a, b);
        assertThat(result.getContent()).allSatisfy(it -> {
            assertThat(it.storeCount()).isZero();
            assertThat(it.lastActivityAt()).isNull();
        });
        verify(jdbcTemplate, never()).queryForList(contains(".stores"), any(Object[].class));
    }

    @Test
    @DisplayName("9-1 — batched schema lookup: database down is rethrown, never shown as zeros")
    void listTenants_batchedLookupDbDown_rethrown() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(UUID.randomUUID(), "FREE", "ACTIVE")));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(jdbcTemplate)
                .query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class));

        assertThatThrownBy(() -> service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25)))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("11.5 — tenant detail looks the schema name up exactly once and reuses it for the metrics")
    void tenantDetail_loadsSchemaNameOnce() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.query(contains("WHERE t.id = ?"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(id, "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForObject(contains("SELECT schema_name FROM public.tenants"), eq(String.class),
                any(Object[].class))).thenReturn("kv_abc123");
        when(jdbcTemplate.queryForList(contains("kv_abc123.stores"), any(Object[].class)))
                .thenReturn(List.of(metrics(3, null, null)));

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
        verify(jdbcTemplate, never()).queryForList(contains("DROP"), any(Object[].class));
        assertThat(com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant()).isNull();
    }

    // ── Spec 9-1 completion ───────────────────────────────────────────────────

    @Test
    @DisplayName("9-1 — plan filter PREMIUM_TRIAL is bound as a parameter (trial ≠ Premium)")
    void listTenants_planFilterTrial_bindsPlan() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        service.execute(new ListTenantsQuery(null, "PREMIUM_TRIAL", null, null, null, null, null, 0, 25));

        verify(jdbcTemplate).queryForObject(contains("AND t.plan_type = ?"), eq(Long.class), eq("PREMIUM_TRIAL"));
    }

    @Test
    @DisplayName("9-1 — plan filter PREMIUM and FREE are exact; legacy PAID still means trial + Premium")
    void listTenants_planFilterPremiumAndLegacyPaid() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        service.execute(new ListTenantsQuery(null, "PREMIUM", null, null, null, null, null, 0, 25));
        verify(jdbcTemplate).queryForObject(contains("AND t.plan_type = ?"), eq(Long.class), eq("PREMIUM"));

        service.execute(new ListTenantsQuery(null, "free", null, null, null, null, null, 0, 25));
        verify(jdbcTemplate).queryForObject(contains("AND t.plan_type = ?"), eq(Long.class), eq("FREE"));

        service.execute(new ListTenantsQuery(null, "PAID", null, null, null, null, null, 0, 25));
        verify(jdbcTemplate).queryForObject(contains("t.plan_type IN ('PREMIUM', 'PREMIUM_TRIAL')"),
                eq(Long.class), any(Object[].class));

        service.execute(new ListTenantsQuery(null, "ALL", null, null, null, null, null, 0, 25));
        verify(jdbcTemplate, times(3)).queryForObject(contains("plan_type"), eq(Long.class), any(Object[].class));
    }

    @Test
    @DisplayName("9-1 — unknown plan filter → 422 VALIDATION_ERROR, no query")
    void listTenants_unknownPlan_validationError() {
        assertThatThrownBy(() -> service.execute(
                new ListTenantsQuery(null, "GOLD", null, null, null, null, null, 0, 25)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("9-1 (routed from 11-5) — exactly TWO cross-schema statements per tenant: metrics, owner name")
    void listTenants_twoCrossSchemaStatementsPerTenant() {
        List<AdminTenantListItem> rows = List.of(
                buildItem(UUID.randomUUID(), "FREE", "ACTIVE"),
                buildItem(UUID.randomUUID(), "PREMIUM", "ACTIVE"),
                buildItem(UUID.randomUUID(), "PREMIUM_TRIAL", "ACTIVE"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
        java.sql.Timestamp last = java.sql.Timestamp.from(Instant.parse("2026-10-12T08:00:00Z"));
        when(jdbcTemplate.queryForList(contains("kv_abc123.stores"), any(Object[].class)))
                .thenReturn(List.of(metrics(4, last, null)));
        stubOwnerName("  Awa Diallo ");

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        // 1 count + 1 list + 1 batched schema lookup + 2N (=6) cross-schema statements, nothing else:
        // store count + last activity together, the owner name apart (a broken employees table
        // must not lose the metrics).
        verify(jdbcTemplate, times(6)).queryForList(anyString(), any(Object[].class));
        verify(jdbcTemplate, times(3)).queryForList(contains("kv_abc123.stores"), any(Object[].class));
        verify(jdbcTemplate, times(3)).queryForList(contains("AS owner_name"), any(Object[].class));
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        verifyNoMoreInteractions(jdbcTemplate);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(q -> assertThat(q).contains("kv_abc123.stores")
                .contains("kv_abc123.audit_log").doesNotContain("employees"));
        assertThat(sql.getAllValues()).anySatisfy(q -> assertThat(q).contains("kv_abc123.employees")
                .contains("role = 'OWNER'").doesNotContain("stores"));
        assertThat(result.getContent()).allSatisfy(it -> {
            assertThat(it.storeCount()).isEqualTo(4);
            assertThat(it.lastActivityAt()).isEqualTo(last.toInstant());
            assertThat(it.ownerName()).isEqualTo("Awa Diallo");
        });
    }

    @Test
    @DisplayName("9-1 — owner without a stored name → « Propriétaire »")
    void listTenants_ownerWithoutName_isProprietaire() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(UUID.randomUUID(), "FREE", "ACTIVE")));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        assertThat(result.getContent()).singleElement()
                .extracting(AdminTenantListItem::ownerName).isEqualTo("Propriétaire");
    }

    @Test
    @DisplayName("9-1 — broken employees table only loses the owner name, never the metrics")
    void listTenants_brokenEmployees_keepsMetrics() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(UUID.randomUUID(), "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForList(contains(".stores"), any(Object[].class)))
                .thenReturn(List.of(metrics(2, null, null)));
        when(jdbcTemplate.queryForList(contains("AS owner_name"), any(Object[].class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql",
                        new java.sql.SQLException("relation employees does not exist")));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));

        assertThat(result.getContent()).singleElement().satisfies(it -> {
            assertThat(it.storeCount()).isEqualTo(2);
            assertThat(it.ownerName()).isEqualTo("Propriétaire");
        });
    }

    @Test
    @DisplayName("9-1 — last-activity filter keeps only tenants whose latest merchant event is in range, then pages")
    void listTenants_lastActivityFilter_preFiltersPerSchema() {
        UUID inRange = UUID.randomUUID();
        UUID tooOld = UUID.randomUUID();
        UUID never = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("GROUP BY t.id ORDER BY t.created_at DESC"), any(Object[].class)))
                .thenReturn(List.of(
                        row("id", inRange.toString(), "schema_name", "kv_aaa111"),
                        row("id", tooOld.toString(), "schema_name", "kv_bbb222"),
                        row("id", never.toString(), "schema_name", "kv_ccc333")));
        when(jdbcTemplate.queryForList(contains("kv_aaa111.audit_log"), any(Object[].class)))
                .thenReturn(List.of(row("last_activity", java.sql.Timestamp.from(Instant.parse("2026-10-05T10:00:00Z")))));
        when(jdbcTemplate.queryForList(contains("kv_bbb222.audit_log"), any(Object[].class)))
                .thenReturn(List.of(row("last_activity", java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")))));
        when(jdbcTemplate.queryForList(contains("kv_ccc333.audit_log"), any(Object[].class)))
                .thenReturn(List.of(row("last_activity", null)));
        when(jdbcTemplate.query(contains("AND t.id IN (?)"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(inRange, "FREE", "ACTIVE")));

        Page<AdminTenantListItem> result = service.execute(new ListTenantsQuery(null, null, null, null, null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-13T00:00:00Z"), 0, 25));

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).extracting(AdminTenantListItem::id).containsExactly(inRange);
        verify(jdbcTemplate).query(contains("AND t.id IN (?)"), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, never()).queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class));
    }

    @Test
    @DisplayName("9-1 — a tenant's own schema error is skipped; a connection failure is rethrown")
    void listTenants_schemaErrorSkipped_resourceFailureRethrown() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(UUID.randomUUID(), "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForList(contains(".stores"), any(Object[].class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql",
                        new java.sql.SQLException("relation does not exist")));

        Page<AdminTenantListItem> result = service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25));
        assertThat(result.getContent()).singleElement().satisfies(it -> {
            assertThat(it.storeCount()).isZero();
            assertThat(it.ownerName()).isEqualTo("Propriétaire");
        });

        reset(jdbcTemplate);
        setUp();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(UUID.randomUUID(), "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForList(contains(".stores"), any(Object[].class)))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("pool exhausted"));

        assertThatThrownBy(() -> service.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25)))
                .isInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
    }

    @Test
    @DisplayName("9-1 — detail employees: trimmed names, « Propriétaire » fallback, last active day, no id")
    void tenantDetail_employeesHaveNamesAndLastActivity() throws Exception {
        UUID id = stubDetail();
        UUID owner = UUID.randomUUID();
        UUID awa = UUID.randomUUID();
        UUID nameless = UUID.randomUUID();
        answerRows("user_daily_activity", List.of(
                row("user_id", owner.toString(), "role", "OWNER"),
                row("user_id", awa.toString(), "role", "EMPLOYEE", "last_day", java.sql.Date.valueOf("2026-10-12")),
                row("user_id", nameless.toString(), "role", "EMPLOYEE")));
        when(jdbcTemplate.queryForList(contains("kv_abc123.employees e WHERE e.user_id IN"), any(Object[].class)))
                .thenReturn(List.of(row("user_id", awa.toString(), "full_name", " Awa Diallo ")));

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        assertThat(detail.employees()).extracting(com.keevo.admin.tenant.domain.model.AdminEmployeeInfo::name)
                .containsExactly("Propriétaire", "Awa Diallo", "Employé");
        assertThat(detail.employees().get(1).lastActivityAt()).isEqualTo(java.time.LocalDate.of(2026, 10, 12));
        assertThat(detail.employees().get(0).lastActivityAt()).isNull();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).query(sql.capture(), any(RowMapper.class), eq(id));
        // members come from public.* only; names from the tenant schema in a separate statement
        assertThat(sql.getAllValues().get(1)).contains("MAX(d.activity_date)").doesNotContain("employees")
                .doesNotContain("u.phone_number");
    }

    @Test
    @DisplayName("9-1 — broken employees table keeps every member (« Propriétaire » / « Employé »)")
    void tenantDetail_brokenEmployees_keepsMembers() throws Exception {
        UUID id = stubDetail();
        answerRows("user_daily_activity", List.of(
                row("user_id", UUID.randomUUID().toString(), "role", "OWNER"),
                row("user_id", UUID.randomUUID().toString(), "role", "EMPLOYEE")));
        when(jdbcTemplate.queryForList(contains(".employees e WHERE e.user_id IN"), any(Object[].class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql",
                        new java.sql.SQLException("relation employees does not exist")));

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        assertThat(detail.employees()).extracting(com.keevo.admin.tenant.domain.model.AdminEmployeeInfo::name)
                .containsExactly("Propriétaire", "Employé");
    }

    @Test
    @DisplayName("9-1 — detail audit: action + entity label + actor name, never entity_id / user_id")
    void tenantDetail_auditLinesAreReadable() throws Exception {
        UUID id = stubDetail();
        Instant at = Instant.parse("2026-10-12T09:00:00Z");
        UUID awa = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("kv_abc123.employees e WHERE e.user_id IN"), any(Object[].class)))
                .thenReturn(List.of(row("user_id", awa.toString(), "full_name", "Awa Diallo")));
        answerRows("kv_abc123.audit_log a", List.of(
                row("action", "PRICE_CHANGED", "entity_type", "Product", "entity_label", "Savon",
                        "source", "ONLINE", "user_id", awa.toString(), "store_name", "Boutique Centre",
                        "occurred_at", java.sql.Timestamp.from(at)),
                row("action", "STOCK_THRESHOLD_BREACHED", "entity_type", "StockAlert", "entity_label", " ",
                        "source", "SYSTEM", "occurred_at", java.sql.Timestamp.from(at)),
                row("action", "PRODUCT_CREATED", "entity_type", "Product", "actor_is_owner", true,
                        "occurred_at", java.sql.Timestamp.from(at)),
                row("action", "PRODUCT_CREATED", "entity_type", "Product",
                        "user_id", UUID.randomUUID().toString())));

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        assertThat(detail.auditEvents()).extracting(com.keevo.admin.tenant.domain.model.AdminAuditEvent::actorName)
                .containsExactly("Awa Diallo", "Système", "Propriétaire", "Utilisateur inconnu");
        var first = detail.auditEvents().get(0);
        assertThat(first.action()).isEqualTo("PRICE_CHANGED");
        assertThat(first.entityLabel()).isEqualTo("Savon");
        assertThat(first.storeName()).isEqualTo("Boutique Centre");
        assertThat(detail.auditEvents().get(1).entityLabel()).isNull();
        // a null occurred_at never turns the drawer into a 500
        assertThat(detail.auditEvents().get(3).occurredAt()).isNull();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(id), eq(50), eq(0L));
        assertThat(sql.getValue()).contains("a.entity_label").doesNotContain("entity_id")
                .doesNotContain("employees");
    }

    private void stubOwnerName(String name) {
        when(jdbcTemplate.queryForList(contains("AS owner_name"), any(Object[].class)))
                .thenReturn(List.of(row("owner_name", name)));
    }

    /** Detail of one tenant on schema kv_abc123; returns its id. */
    @Test
    @DisplayName("9-2 — detail: unreadable subscription table → plan null, the rest of the detail still served")
    void getTenantDetail_subscriptionUnreadable_planNull() {
        UUID id = stubDetail();
        when(jdbcTemplate.query(contains("\"kv_abc123\".subscriptions"), any(RowMapper.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql",
                        new java.sql.SQLException("relation subscriptions does not exist")));

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        assertThat(detail.plan()).isNull();
        assertThat(detail.summary().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("9-2 — detail: suspended tenant → suspension date and reason from public.tenants")
    void getTenantDetail_suspensionTrace() {
        UUID id = stubDetail();
        when(jdbcTemplate.queryForList(contains("suspended_reason"), eq(id))).thenReturn(List.of(
                row("suspended_at", java.sql.Timestamp.from(Instant.parse("2026-10-12T09:00:00Z")),
                        "suspended_reason", "Impayés")));

        var detail = service.execute(new com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery(id, 0));

        assertThat(detail.suspension()).isEqualTo(new com.keevo.admin.tenant.domain.model.AdminSuspensionInfo(
                Instant.parse("2026-10-12T09:00:00Z"), "Impayés"));
    }

    private UUID stubDetail() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.query(contains("WHERE t.id = ?"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(buildItem(id, "FREE", "ACTIVE")));
        when(jdbcTemplate.queryForObject(contains("SELECT schema_name FROM public.tenants"), eq(String.class),
                any(Object[].class))).thenReturn("kv_abc123");
        return id;
    }

    /** Runs the RowMapper of the matching query over mocked rows (absent column → null / false). */
    @SuppressWarnings("unchecked")
    private void answerRows(String sqlFragment, List<Map<String, Object>> rows) throws Exception {
        doAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> out = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> r = rows.get(i);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(a -> {
                    Object v = r.get(a.<String>getArgument(0));
                    return v == null ? null : v.toString();
                });
                when(rs.getDate(anyString())).thenAnswer(a -> r.get(a.<String>getArgument(0)));
                when(rs.getTimestamp(anyString())).thenAnswer(a -> r.get(a.<String>getArgument(0)));
                when(rs.getBoolean(anyString())).thenAnswer(a -> Boolean.TRUE.equals(r.get(a.<String>getArgument(0))));
                out.add(mapper.mapRow(rs, i));
            }
            return out;
        }).when(jdbcTemplate).query(contains(sqlFragment), any(RowMapper.class), any(Object[].class));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
