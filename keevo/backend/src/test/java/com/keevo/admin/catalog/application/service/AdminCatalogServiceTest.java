package com.keevo.admin.catalog.application.service;

import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests of {@link AdminCatalogService}. Story 19.11b: the per-tenant reads go through {@link CrossTenantSql}
 * (mocked): branch SQL is checked by applying the captured writer to a tenant, the Java merge / paging by feeding
 * chunk rows; the SQL itself runs on PostgreSQL in {@code AdminScreensCrossTenantIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCatalogService")
class AdminCatalogServiceTest {

    private static final TenantBranch FULL = new TenantBranch("11111111-1111-1111-1111-111111111111", "A",
            "kv_aaaaaa", "ACTIVE", null, EnumSet.allOf(TenantTable.class));

    @Mock JdbcTemplate jdbc;
    @Mock CrossTenantSql crossTenant;

    AdminCatalogService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdminCatalogService(jdbc, crossTenant);
        lenient().when(crossTenant.withPresence(anyList())).thenAnswer(inv -> ((List<TenantBranch>) inv.getArgument(0))
                .stream().map(t -> t.withTables(EnumSet.allOf(TenantTable.class))).toList());
        lenient().doReturn(List.of()).when(crossTenant).query(anyList(), any(), any(), any());
    }

    // ── listProducts ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listProducts merges the chunk rows (most recent first) and counts with one COUNT union")
    void listProducts_mergesChunks() {
        stubTenants("t1", "kv_aaaaaa", "t2", "kv_bbbbbb");
        stubChunks(List.of(3L),
                product("t1", "p1", "Ancien", Instant.parse("2026-10-01T00:00:00Z")),
                product("t2", "p2", "Récent", Instant.parse("2026-10-05T00:00:00Z")),
                product("t1", "p3", "Moyen", Instant.parse("2026-10-03T00:00:00Z")));

        Page<AdminProductListItem> result = service.execute(new ListProductsQuery(null, null, null, null, null, 0, 2));

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).extracting(AdminProductListItem::name).containsExactly("Récent", "Moyen");
        assertThat(result.getContent().get(0).tenantName()).isEqualTo("Tenant t2");
        assertThat(result.getContent().get(0).schemaName()).isEqualTo("kv_bbbbbb");
        // Each branch / chunk returns at most offset + size rows, in the global order.
        assertThat(outer(1, "U")).isEqualTo("SELECT * FROM (U) u ORDER BY updated_at DESC NULLS LAST, "
                + "tenant_id COLLATE \"C\", id COLLATE \"C\" LIMIT 2");
        assertThat(outer(0, "U")).isEqualTo("SELECT COALESCE(SUM(n), 0) FROM (U) u");
    }

    @Test
    @DisplayName("page 2: each branch reads offset + size rows, the page is cut after the merge")
    void listProducts_secondPage() {
        stubTenants("t1", "kv_aaaaaa");
        stubChunks(List.of(3L),
                product("t1", "p1", "A", Instant.parse("2026-10-03T00:00:00Z")),
                product("t1", "p2", "B", Instant.parse("2026-10-02T00:00:00Z")),
                product("t1", "p3", "C", Instant.parse("2026-10-01T00:00:00Z")));

        Page<AdminProductListItem> result = service.execute(new ListProductsQuery(null, null, null, null, null, 1, 2));

        assertThat(result.getContent()).extracting(AdminProductListItem::name).containsExactly("C");
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(branchSql(1, FULL)).endsWith("LIMIT 4");
    }

    @Test
    @DisplayName("a page beyond the total reads no row (count only)")
    void listProducts_pageBeyondTotal() {
        stubTenants("t1", "kv_aaaaaa");
        stubChunks(List.of(3L));

        Page<AdminProductListItem> result = service.execute(new ListProductsQuery(null, null, null, null, null, 5, 25));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(3);
        verify(crossTenant, times(1)).query(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("export (page size MAX) reads every row, without LIMIT")
    void listProducts_export() {
        stubTenants("t1", "kv_aaaaaa");
        stubChunks(List.of(2L),
                product("t1", "p1", "A", Instant.parse("2026-10-03T00:00:00Z")),
                product("t1", "p2", "B", null));

        Page<AdminProductListItem> result = service.execute(
                new ListProductsQuery(null, null, null, null, null, 0, Integer.MAX_VALUE));

        assertThat(result.getContent()).extracting(AdminProductListItem::name).containsExactly("A", "B");
        assertThat(outer(1, "U")).isEqualTo("SELECT * FROM (U) u");
        assertThat(branchSql(1, FULL)).doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("negative page → VALIDATION_ERROR, nothing read")
    void listProducts_negativePage() {
        assertThatThrownBy(() -> service.execute(new ListProductsQuery(null, null, null, null, null, -1, 25)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(jdbc, crossTenant);
    }

    @Test
    @DisplayName("listProducts with tenantId filter reads only the target tenant")
    @SuppressWarnings("unchecked")
    void listProducts_withTenantFilter_queriesOnlyTargetSchema() {
        String targetId = UUID.randomUUID().toString();
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            assertThat(sql).contains("t.id = ?::uuid");
            RowMapper<Object> rm = inv.getArgument(1);
            return List.of(buildTenantRow(rm, targetId, "Target Tenant", "kv_target", "FREE"));
        }).when(jdbc).query(contains("FROM public.tenants"), any(RowMapper.class), any(Object[].class));

        service.execute(new ListProductsQuery(null, targetId, null, null, null, 0, 25));

        assertThat(queriedTenants(0)).containsExactly(targetId);
    }

    @Test
    @DisplayName("filters are bound in the branch: search lower(name) LIKE (trigram, 19.11c), status, stockLevel=OUT; "
            + "categories joined when present")
    void listProducts_filtersInBranch() {
        stubTenants("t1", "kv_aaaaaa");
        stubChunks(List.of(1L), product("t1", "p1", "A", null));

        service.execute(new ListProductsQuery("riz_50%", null, "OUT", "ACTIVE", null, 0, 25));

        List<Object> params = new ArrayList<>();
        String sql = branchSql(1, FULL, params);
        assertThat(sql).contains("FROM kv_aaaaaa.products p LEFT JOIN kv_aaaaaa.categories c ON c.id = p.category_id")
                .contains("lower(p.name) LIKE lower(?) ESCAPE '\\'").contains("p.status = ?")
                .contains("p.stock_quantity = 0");
        // the typed % and _ are literal characters
        assertThat(params).containsExactly("%riz\\_50\\%%", "ACTIVE");
        String noCategories = branchSql(1, new TenantBranch(FULL.id(), "A", "kv_aaaaaa", "ACTIVE", null,
                EnumSet.of(TenantTable.PRODUCTS_LIST)), new ArrayList<>());
        assertThat(noCategories).doesNotContain("categories").contains("'' AS category_name");
    }

    @Test
    @DisplayName("tenants without the products table are not branches")
    void listProducts_onlyTenantsWithProducts() {
        stubTenants("t1", "kv_aaaaaa", "t2", "kv_bbbbbb");
        when(crossTenant.withPresence(anyList())).thenAnswer(inv -> {
            List<TenantBranch> in = inv.getArgument(0);
            return List.of(in.get(0).withTables(EnumSet.of(TenantTable.PRODUCTS_LIST)),
                    in.get(1).withTables(EnumSet.of(TenantTable.STORES)));
        });

        service.execute(new ListProductsQuery(null, null, null, null, null, 0, 25));

        assertThat(queriedTenants(0)).containsExactly("t1");
    }

    // ── getCatalogSummary ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getCatalogSummary sums the six counts of every chunk")
    @SuppressWarnings("unchecked")
    void getCatalogSummary_sumsChunks() {
        doAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            return List.of(buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"),
                    buildTenantRow(rm, "t2", "Tenant B", "kv_bbbbbb", "FREE"));
        }).when(jdbc).query(anyString(), any(RowMapper.class));
        doReturn(List.of(new long[]{10, 7, 1, 2, 1, 3}, new long[]{5, 4, 0, 1, 0, 1}))
                .when(crossTenant).query(anyList(), any(), any(), any());

        AdminCatalogSummary summary = service.execute();

        assertThat(summary).isEqualTo(new AdminCatalogSummary(15, 11, 1, 3, 1, 4));
        String sql = branchSql(0, FULL, new ArrayList<>());
        // 17.10: active = ACTIVE and not archived (same count as /subscription/me and the plan limit);
        // 17.12: untracked products are never « low » / « out of stock ».
        assertThat(sql).contains("COUNT(*) FILTER (WHERE status = 'ACTIVE' AND archived = false)")
                .contains("stock_quantity > 0 AND stock_quantity <= 5 AND track_stock = true")
                .contains("stock_quantity = 0 AND track_stock = true")
                .contains("created_at >= NOW() - INTERVAL '7 days'")
                .contains("FROM kv_aaaaaa.products");
    }

    // ── Story 19.7a — plan class by kind ─────────────────────────────────────

    @Test
    @DisplayName("19.7a: a trial or paid kind is « PAID », the free kind or a code unknown to the catalogue « FREE »")
    void planClassByKind() {
        assertThat(AdminCatalogService.planClass("TRIAL")).isEqualTo("PAID");
        assertThat(AdminCatalogService.planClass("PAID")).isEqualTo("PAID");
        assertThat(AdminCatalogService.planClass("FREE")).isEqualTo("FREE");
        assertThat(AdminCatalogService.planClass(null)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("19.7a: the PAID filter binds the kinds TRIAL and PAID, the FREE filter the kind FREE (or unknown); "
            + "a TRIAL-kind tenant row maps to PAID")
    @SuppressWarnings("unchecked")
    void planFiltersBindKinds() {
        List<String> sqls = new ArrayList<>();
        List<List<Object>> params = new ArrayList<>();
        List<Object> mapped = new ArrayList<>();
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("FROM public.tenants")) {
                sqls.add(sql);
                Object[] args = java.util.Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length);
                params.add(List.of(args));
                RowMapper<Object> rm = inv.getArgument(1);
                mapped.add(buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "PREMIUM_TRIAL"));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        service.execute(new ListProductsQuery(null, null, null, null, "PAID", 0, 25));
        service.execute(new ListProductsQuery(null, null, null, null, "FREE", 0, 25));

        assertThat(sqls.get(0)).contains("LEFT JOIN public.plans pl").contains("pl.kind IN (?, ?)");
        assertThat(params.get(0)).containsExactly("TRIAL", "PAID");
        assertThat(sqls.get(1)).contains("(pl.kind = ? OR pl.kind IS NULL)");
        assertThat(params.get(1)).containsExactly("FREE");
        assertThat(mapped.get(0).toString()).contains("PAID");
    }

    @Test
    @DisplayName("v1s-13-12 follow-up: a tenant row with an invalid schema_name is skipped, never interpolated")
    @SuppressWarnings("unchecked")
    void listProducts_invalidSchemaRow_isSkipped() {
        doAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            return List.of(buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"),
                    buildTenantRow(rm, "t2", "Evil", "kv_x\"; DROP", "FREE"));
        }).when(jdbc).query(contains("FROM public.tenants"), any(RowMapper.class), any(Object[].class));

        service.execute(new ListProductsQuery(null, null, null, null, null, 0, 25));

        assertThat(queriedTenants(0)).containsExactly("t1");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("19.11c — a malformed company id is 422 « Compte invalide », no tenant query")
    void malformedTenantId_validationError() {
        assertThatThrownBy(() -> service.prepareExport(
                new ListProductsQuery(null, "pas-un-uuid", null, null, null, 0, Integer.MAX_VALUE)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("19.11c — export: prepared (tenants + presence), then streamed chunk by chunk, grouped by company; no count")
    @SuppressWarnings("unchecked")
    void prepareExport_streamsChunks() {
        String T1 = "11111111-1111-1111-1111-111111111111";
        String T2 = "22222222-2222-2222-2222-222222222222";
        stubTenants(T1, "kv_aaaaaa", T2, "kv_bbbbbb");
        doAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(4);
            java.util.function.Consumer<List<Object>> sink = inv.getArgument(5);
            sink.accept(List.of(mapper.mapRow(resultSet(product(T1, "p1", "A", Instant.parse("2026-10-01T00:00:00Z"))), 0)));
            sink.accept(java.util.Arrays.asList(mapper.mapRow(resultSet(product(T2, "p2", "B", Instant.parse("2026-10-05T00:00:00Z"))), 0),
                    mapper.mapRow(resultSet(product("unknown", "p3", "C", null)), 1)));
            return null;
        }).when(crossTenant).forEachChunk(anyList(), anyInt(), any(), any(), any(), any());

        AdminCatalogService.ProductExport export = service.prepareExport(
                new ListProductsQuery(null, null, null, null, null, 0, Integer.MAX_VALUE));
        verify(crossTenant, never()).forEachChunk(anyList(), anyInt(), any(), any(), any(), any()); // nothing read before forEach
        List<AdminProductListItem> rows = new ArrayList<>();
        export.forEach(rows::add);

        // chunk order kept (company order), unknown tenant rows dropped, never a COUNT union
        assertThat(rows).extracting(AdminProductListItem::name).containsExactly("A", "B");
        verify(crossTenant, never()).query(anyList(), any(), any(), any());
        ArgumentCaptor<CrossTenantSql.BranchSql> branch = ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        ArgumentCaptor<UnaryOperator<String>> outer = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(crossTenant).forEachChunk(anyList(), eq(AdminCatalogService.EXPORT_CHUNK), branch.capture(), outer.capture(),
                any(), any());
        TenantBranch second = new TenantBranch(T2, "B", "kv_bbbbbb", "ACTIVE", null, EnumSet.allOf(TenantTable.class));
        assertThat(branch.getValue().sql(second, new ArrayList<>())).startsWith("SELECT 1 AS pos, ")
                .doesNotContain("LIMIT");
        assertThat(outer.getValue().apply("U")).isEqualTo("SELECT * FROM (U) u ORDER BY pos, updated_at DESC NULLS LAST, "
                + "tenant_id COLLATE \"C\", id COLLATE \"C\"");
    }

    /** Live tenants (id, schema) pairs answered by the tenant query of the list. */
    @SuppressWarnings("unchecked")
    private void stubTenants(String... idSchema) {
        doAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < idSchema.length; i += 2) {
                out.add(buildTenantRow(rm, idSchema[i], "Tenant " + idSchema[i], idSchema[i + 1], "FREE"));
            }
            return out;
        }).when(jdbc).query(contains("FROM public.tenants"), any(RowMapper.class), any(Object[].class));
    }

    /** First cross-tenant call (COUNT) answers {@code counts}; the second maps {@code rows} with the service mapper. */
    @SafeVarargs
    private void stubChunks(List<Long> counts, Map<String, Object>... rows) {
        int[] call = {0};
        doAnswer(inv -> {
            if (call[0]++ == 0) return counts;
            RowMapper<?> mapper = inv.getArgument(3);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < rows.length; i++) out.add(mapper.mapRow(resultSet(rows[i]), i));
            return out;
        }).when(crossTenant).query(anyList(), any(), any(), any());
    }

    private String branchSql(int call, TenantBranch tenant) {
        return branchSql(call, tenant, new ArrayList<>());
    }

    private String branchSql(int call, TenantBranch tenant, List<Object> params) {
        ArgumentCaptor<CrossTenantSql.BranchSql> writer = ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant, atLeastOnce()).query(anyList(), writer.capture(), any(), any());
        return writer.getAllValues().get(call).sql(tenant, params);
    }

    @SuppressWarnings("unchecked")
    private String outer(int call, String union) {
        ArgumentCaptor<UnaryOperator<String>> outer = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(crossTenant, atLeastOnce()).query(anyList(), any(), outer.capture(), any());
        return outer.getAllValues().get(call).apply(union);
    }

    @SuppressWarnings("unchecked")
    private List<String> queriedTenants(int call) {
        ArgumentCaptor<List<TenantBranch>> tenants = ArgumentCaptor.forClass(List.class);
        verify(crossTenant, atLeastOnce()).query(tenants.capture(), any(), any(), any());
        return tenants.getAllValues().get(call).stream().map(TenantBranch::id).toList();
    }

    private static Map<String, Object> product(String tenantId, String id, String name, Instant updatedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("tenant_id", tenantId);
        m.put("id", id);
        m.put("name", name);
        m.put("price", 1000L);
        m.put("stock_quantity", 3);
        m.put("status", "ACTIVE");
        m.put("category_name", "");
        m.put("updated_at", updatedAt == null ? null : Timestamp.from(updatedAt));
        return m;
    }

    private static ResultSet resultSet(Map<String, Object> row) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(i -> {
            Object v = row.get(i.<String>getArgument(0));
            return v == null ? null : v.toString();
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(i -> {
            Object v = row.get(i.<String>getArgument(0));
            return v instanceof Number n ? n.longValue() : 0L;
        });
        lenient().when(rs.getInt(anyString())).thenAnswer(i -> {
            Object v = row.get(i.<String>getArgument(0));
            return v instanceof Number n ? n.intValue() : 0;
        });
        lenient().when(rs.getTimestamp(anyString())).thenAnswer(i -> (Timestamp) row.get(i.<String>getArgument(0)));
        return rs;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object buildTenantRow(RowMapper rm, String id, String name, String schema, String planType) {
        try {
            ResultSet rs = mock(ResultSet.class);
            lenient().when(rs.getString("id")).thenReturn(id);
            lenient().when(rs.getString("name")).thenReturn(name);
            lenient().when(rs.getString("schema_name")).thenReturn(schema);
            // Story 19.7a: the plan class is read from the kind joined from the catalogue
            lenient().when(rs.getString("kind"))
                    .thenReturn(com.keevo.identity.api.PlanType.of(planType).seedKind().orElseThrow().name());
            return rm.mapRow(rs, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
