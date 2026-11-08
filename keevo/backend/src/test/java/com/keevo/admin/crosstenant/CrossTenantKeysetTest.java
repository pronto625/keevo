package com.keevo.admin.crosstenant;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("20.13 — cross-tenant keyset: cursor, sort predicate, bounded union, params")
class CrossTenantKeysetTest {

    private static final KeysetSort SORT = new KeysetSort(List.of(
            new KeysetSort.Key("c.updated_at", "timestamptz"), new KeysetSort.Key("CAST(c.id AS text)", "text")), true);

    @Test
    @DisplayName("a cursor round-trips its tenant and keys for the same filters")
    void cursorRoundTrip() {
        String cursor = KeysetCursor.encode("customers|a", "t-2", List.of("2026-11-08 10:00:00+01", "id-9"));

        KeysetCursor decoded = KeysetCursor.decode(cursor, "customers|a", 2);

        assertThat(decoded.tenantId()).isEqualTo("t-2");
        assertThat(decoded.keys()).containsExactly("2026-11-08 10:00:00+01", "id-9");
        assertThat(KeysetCursor.decode(null, "x", 2)).isNull();
        assertThat(KeysetCursor.decode("  ", "x", 2)).isNull();
    }

    @Test
    @DisplayName("a cursor reused with other filters, tampered, too long or with the wrong key count → 400")
    void cursorRefused() {
        String cursor = KeysetCursor.encode("customers|a", "t-2", List.of("k0", "k1"));

        for (String bad : List.of("not-base64!!", cursor.substring(1), "x".repeat(2000))) {
            assertInvalid(() -> KeysetCursor.decode(bad, "customers|a", 2));
        }
        assertInvalid(() -> KeysetCursor.decode(cursor, "customers|b", 2));
        assertInvalid(() -> KeysetCursor.decode(cursor, "customers|a", 3));
    }

    @Test
    @DisplayName("first page: no keyset predicate; select keys and orders follow the sort")
    void firstPage() {
        List<Object> params = new ArrayList<>();

        assertThat(SORT.predicate("t1", null, params)).isEqualTo("TRUE");
        assertThat(params).isEmpty();
        assertThat(SORT.selectKeys()).isEqualTo("c.updated_at AS k0, CAST(c.id AS text) AS k1");
        assertThat(SORT.branchOrderBy()).isEqualTo("c.updated_at DESC, CAST(c.id AS text) DESC");
        assertThat(SORT.outerOrderBy()).isEqualTo("k0 DESC, k1 DESC, tenant_id COLLATE \"C\" DESC");
    }

    @Test
    @DisplayName("descending: a branch of a tenant sorting before the cursor tenant includes equal keys (<=)")
    void descendingTieBreak() {
        KeysetCursor cursor = new KeysetCursor("m", List.of("2026-11-08", "id"));
        List<Object> params = new ArrayList<>();

        assertThat(SORT.predicate("a", cursor, params))
                .isEqualTo("(c.updated_at, CAST(c.id AS text)) <= (CAST(? AS timestamptz), CAST(? AS text))");
        assertThat(SORT.predicate("m", cursor, params)).contains(") < (");
        assertThat(SORT.predicate("z", cursor, params)).contains(") < (");
        assertThat(params).containsExactly("2026-11-08", "id", "2026-11-08", "id", "2026-11-08", "id");
    }

    @Test
    @DisplayName("ascending mirrors it: a later tenant includes equal keys (>=)")
    void ascendingTieBreak() {
        KeysetSort asc = new KeysetSort(List.of(new KeysetSort.Key("x.k", "text")), false);
        KeysetCursor cursor = new KeysetCursor("m", List.of("v"));

        assertThat(asc.predicate("z", cursor, new ArrayList<>())).isEqualTo("(x.k) >= (CAST(? AS text))");
        assertThat(asc.predicate("a", cursor, new ArrayList<>())).isEqualTo("(x.k) > (CAST(? AS text))");
    }

    @Test
    @DisplayName("union: one parenthesised branch per tenant; more branches than the bound → 400")
    void unionBounded() {
        CrossTenantSql sql = new CrossTenantSql(mock(JdbcTemplate.class), 2);
        List<TenantBranch> two = tenants(2);

        assertThat(sql.unionAll(two, t -> "SELECT 1 FROM " + t.schemaName() + ".x"))
                .isEqualTo("(SELECT 1 FROM kv_aaaaa0.x) UNION ALL (SELECT 1 FROM kv_aaaaa1.x)");
        assertThatThrownBy(() -> sql.unionAll(tenants(3), t -> "SELECT 1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("filtrez la liste par compte");
    }

    @Test
    @DisplayName("tenant literal is a parsed UUID; ids and params are validated")
    void literalsAndParams() {
        TenantBranch t = new TenantBranch("6f1c2a52-3c1e-4b8e-9a77-0d4f2c9b1e11", "A", "kv_aaaaaa", "ACTIVE",
                Instant.now());
        assertThat(CrossTenantSql.tenantLiteral(t)).isEqualTo("'6f1c2a52-3c1e-4b8e-9a77-0d4f2c9b1e11'");
        assertThatThrownBy(() -> CrossTenantSql.tenantLiteral(new TenantBranch("x'; DROP", "A", "kv_aaaaaa", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CrossTenantSql.requireUuid(null, "m")).isNull();
        assertInvalid(() -> CrossTenantSql.requireUuid("abc", "Compte invalide"));
        assertThat(AdminParams.limit(null)).isEqualTo(20);
        assertInvalid(() -> AdminParams.limit("0"));
        assertInvalid(() -> AdminParams.limit("101"));
        assertInvalid(() -> AdminParams.limit("x"));
        assertInvalid(() -> AdminParams.search("a".repeat(101)));
        assertInvalid(() -> AdminParams.date("2026-13-01", "La date"));
        assertInvalid(() -> AdminParams.flag("oui", "Le filtre"));
        assertThat(AdminParams.likePattern("A_b%c\\")).isEqualTo("%a\\_b\\%c\\\\%");
    }

    private static List<TenantBranch> tenants(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new TenantBranch("id" + i, "T" + i, "kv_aaaaa" + i, "ACTIVE", null)).toList();
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
