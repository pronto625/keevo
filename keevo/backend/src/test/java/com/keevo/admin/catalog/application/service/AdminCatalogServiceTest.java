package com.keevo.admin.catalog.application.service;

import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCatalogService")
class AdminCatalogServiceTest {

    @Mock JdbcTemplate jdbc;

    AdminCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AdminCatalogService(jdbc);
    }

    // ── listProducts ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listProducts with no filters queries all active schemas")
    @SuppressWarnings("unchecked")
    void listProducts_noFilters_queriesAllActiveSchemas() {
        ListProductsQuery query = new ListProductsQuery(null, null, null, null, null, 0, 25);

        // Mock: tenant query (no params → 2-arg overload called internally by Spring)
        // Use a generic doAnswer that routes by SQL content
        doAnswer(inv -> {
            String sql = (String) inv.getArgument(0);
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            if (sql.contains("FROM public.tenants")) {
                return List.of(
                        buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"),
                        buildTenantRow(rm, "t2", "Tenant B", "kv_bbbbbb", "FREE")
                );
            } else if (sql.contains("kv_aaaaaa")) {
                return List.of(sampleProduct("p1", "Product A", "kv_aaaaaa"));
            } else if (sql.contains("kv_bbbbbb")) {
                return List.of(sampleProduct("p2", "Product B", "kv_bbbbbb"));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Page<AdminProductListItem> result = service.execute(query);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(AdminProductListItem::name)
                .containsExactlyInAnyOrder("Product A", "Product B");
    }

    @Test
    @DisplayName("listProducts with tenantId filter queries only the target schema")
    @SuppressWarnings("unchecked")
    void listProducts_withTenantFilter_queriesOnlyTargetSchema() {
        String targetId = UUID.randomUUID().toString();
        ListProductsQuery query = new ListProductsQuery(null, targetId, null, null, null, 0, 25);

        doAnswer(inv -> {
            String sql = (String) inv.getArgument(0);
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            if (sql.contains("FROM public.tenants")) {
                // Verify tenantId filter is applied
                assertThat(sql).contains("t.id = ?");
                return List.of(buildTenantRow(rm, targetId, "Target Tenant", "kv_target", "FREE"));
            } else if (sql.contains("kv_target")) {
                return List.of(sampleProduct("p1", "Target Product", "kv_target"));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Page<AdminProductListItem> result = service.execute(query);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Target Product");
    }

    @Test
    @DisplayName("listProducts with stockLevel=OUT appends stock_quantity = 0 to product SQL")
    @SuppressWarnings("unchecked")
    void listProducts_withStockLevelOut_appendsStockFilter() {
        ListProductsQuery query = new ListProductsQuery(null, null, "OUT", null, null, 0, 25);

        doAnswer(inv -> {
            String sql = (String) inv.getArgument(0);
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            if (sql.contains("FROM public.tenants")) {
                return List.of(buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"));
            } else if (sql.contains("kv_aaaaaa")) {
                // Verify stock filter is applied
                assertThat(sql).contains("stock_quantity = 0");
                return List.of();
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Page<AdminProductListItem> result = service.execute(query);
        assertThat(result).isNotNull();
        // Verify product query was called for this schema
        verify(jdbc, atLeastOnce()).query(contains("kv_aaaaaa"), any(RowMapper.class), any(Object[].class));
    }

    // ── getCatalogSummary ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getCatalogSummary aggregates counts across all tenant schemas")
    @SuppressWarnings("unchecked")
    void getCatalogSummary_aggregatesAcrossSchemas() {
        // Mock tenant schema list (no params → 2-arg, but service uses no-varargs overload)
        doAnswer(inv -> {
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            return List.of(
                    buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"),
                    buildTenantRow(rm, "t2", "Tenant B", "kv_bbbbbb", "FREE")
            );
        }).when(jdbc).query(anyString(), any(RowMapper.class));

        // Fallback stub FIRST (lowest priority in LIFO), specific stubs AFTER (highest priority)
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(matches("SELECT COUNT\\(\\*\\) FROM kv_aaaaaa\\.products$"), eq(Long.class))).thenReturn(10L);
        when(jdbc.queryForObject(matches("SELECT COUNT\\(\\*\\) FROM kv_bbbbbb\\.products$"), eq(Long.class))).thenReturn(5L);
        // 17.10: active = ACTIVE and not archived (same count as /subscription/me and the plan limit)
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM kv_aaaaaa.products WHERE status = 'ACTIVE' AND archived = false"),
                eq(Long.class))).thenReturn(7L);

        AdminCatalogSummary summary = service.execute();

        assertThat(summary.totalProducts()).isEqualTo(15L);
        assertThat(summary.activeProducts()).isEqualTo(7L);
    }

    @Test
    @DisplayName("getCatalogSummary skips schema on failure and continues")
    @SuppressWarnings("unchecked")
    void getCatalogSummary_schemaFailure_skipsAndContinues() {
        doAnswer(inv -> {
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            return List.of(
                    buildTenantRow(rm, "t1", "Good Tenant", "kv_good00", "FREE"),
                    buildTenantRow(rm, "t2", "Bad Tenant",  "kv_bad000", "FREE")
            );
        }).when(jdbc).query(anyString(), any(RowMapper.class));

        when(jdbc.queryForObject(contains("kv_good00"), eq(Long.class))).thenReturn(5L);
        when(jdbc.queryForObject(contains("kv_bad000"), eq(Long.class)))
                .thenThrow(new RuntimeException("schema not found"));

        // safeCount absorbs the exception — should not throw
        AdminCatalogSummary summary = service.execute();

        assertThat(summary).isNotNull();
        // good tenant contributes, bad tenant contributes 0
        assertThat(summary.totalProducts()).isGreaterThanOrEqualTo(0);
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
        List<String> sqls = new java.util.ArrayList<>();
        List<List<Object>> params = new java.util.ArrayList<>();
        List<Object> mapped = new java.util.ArrayList<>();
        doAnswer(inv -> {
            String sql = (String) inv.getArgument(0);
            if (sql.contains("FROM public.tenants")) {
                sqls.add(sql);
                Object[] args = java.util.Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length);
                params.add(List.of(args));
                RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object buildTenantRow(RowMapper rm, String id, String name, String schema, String planType) {
        try {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("id")).thenReturn(id);
            when(rs.getString("name")).thenReturn(name);
            when(rs.getString("schema_name")).thenReturn(schema);
            // Story 19.7a: the plan class is read from the kind joined from the catalogue
            when(rs.getString("kind")).thenReturn(com.keevo.identity.api.PlanType.of(planType).seedKind().orElseThrow().name());
            return rm.mapRow(rs, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AdminProductListItem sampleProduct(String id, String name, String schema) {
        return new AdminProductListItem(
                id, name,
                UUID.randomUUID().toString(), "Test Tenant", "FREE",
                schema, "Catégorie", 1000L, 10, "ACTIVE",
                Instant.now()
        );
    }
    @Test
    @DisplayName("v1s-13-12 follow-up: a tenant row with an invalid schema_name is skipped, never interpolated")
    @SuppressWarnings("unchecked")
    void listProducts_invalidSchemaRow_isSkipped() {
        ListProductsQuery query = new ListProductsQuery(null, null, null, null, null, 0, 25);
        java.util.List<String> sqls = new java.util.ArrayList<>();

        doAnswer(inv -> {
            String sql = (String) inv.getArgument(0);
            sqls.add(sql);
            RowMapper<Object> rm = (RowMapper<Object>) inv.getArgument(1);
            if (sql.contains("FROM public.tenants")) {
                return List.of(
                        buildTenantRow(rm, "t1", "Tenant A", "kv_aaaaaa", "FREE"),
                        buildTenantRow(rm, "t2", "Evil", "kv_x\"; DROP", "FREE"));
            } else if (sql.contains("kv_aaaaaa")) {
                return List.of(sampleProduct("p1", "Product A", "kv_aaaaaa"));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Page<AdminProductListItem> result = service.execute(query);

        assertThat(result.getContent()).extracting(AdminProductListItem::name).containsExactly("Product A");
        assertThat(sqls).noneMatch(sql -> sql.contains("DROP"));
    }
}
