package com.keevo.admin.crosstenant;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("20.13 — cross-tenant keyset: typed cursor, sort predicate, merge order, union, params")
class CrossTenantKeysetTest {

    private static final String T1 = "11111111-1111-1111-1111-111111111111";
    private static final String T2 = "22222222-2222-2222-2222-222222222222";
    private static final KeysetSort SORT = new KeysetSort(List.of(
            new KeysetSort.Key("c.updated_at", "timestamptz"), new KeysetSort.Key("CAST(c.id AS text)", "text")), true);
    private static final KeysetSort EXPENSES = new KeysetSort(List.of(new KeysetSort.Key("e.expense_date", "date"),
            new KeysetSort.Key("e.created_at", "timestamptz"), new KeysetSort.Key("e.id", "uuid")), true);

    @Test
    @DisplayName("a cursor round-trips its tenant and typed keys for the same filters")
    void cursorRoundTrip() {
        String cursor = KeysetCursor.encode("customers|a", T2, List.of("2026-11-08T09:00:00.123456Z", "id-9"));

        KeysetCursor decoded = KeysetCursor.decode(cursor, "customers|a", SORT);

        assertThat(decoded.tenantId()).isEqualTo(T2);
        assertThat(decoded.keys()).containsExactly("2026-11-08T09:00:00.123456Z", "id-9");
        assertThat(KeysetCursor.decode(null, "x", SORT)).isNull();
        assertThat(KeysetCursor.decode("  ", "x", SORT)).isNull();
    }

    @Test
    @DisplayName("tampered cursors → 422 « Curseur invalide » (never a SQL error): filters, count, types, tenant")
    void cursorRefused() {
        String cursor = KeysetCursor.encode("customers|a", T2, List.of("2026-11-08T09:00:00Z", "k1"));

        for (String bad : List.of("not-base64!!", cursor.substring(1), "x".repeat(2000))) {
            assertInvalid(() -> KeysetCursor.decode(bad, "customers|a", SORT));
        }
        assertInvalid(() -> KeysetCursor.decode(cursor, "customers|b", SORT));
        assertInvalid(() -> KeysetCursor.decode(cursor, "customers|a", EXPENSES));
        // well-formed envelope, key of the wrong type
        assertInvalid(() -> KeysetCursor.decode(KeysetCursor.encode("f", T1, List.of("hier", "k")), "f", SORT));
        assertInvalid(() -> KeysetCursor.decode(
                KeysetCursor.encode("f", T1, List.of("2026-13-45", "2026-11-08T09:00:00Z", T1)), "f", EXPENSES));
        assertInvalid(() -> KeysetCursor.decode(
                KeysetCursor.encode("f", T1, List.of("2026-11-08", "2026-11-08T09:00:00Z", "pas-un-uuid")), "f",
                EXPENSES));
        // tenant slot neither a UUID nor « public »
        assertInvalid(() -> KeysetCursor.decode(
                KeysetCursor.encode("f", "x'; DROP", List.of("2026-11-08T09:00:00Z", "k")), "f", SORT));
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1\u001f" + KeysetCursor.digest("f") + "\u001f" + T1 + "\u001f2026-11-08T09:00:00Z\u001f"
                        + "x".repeat(300)).getBytes());
        assertInvalid(() -> KeysetCursor.decode(forged, "f", SORT));
        assertThat(KeysetCursor.decode(KeysetCursor.encode("f", "public", List.of("2026-11-08T09:00:00Z", "k")),
                "f", SORT).tenantId()).isEqualTo("public");
    }

    @Test
    @DisplayName("first page: no keyset predicate; select keys and orders follow the sort")
    void firstPage() {
        List<Object> params = new ArrayList<>();

        assertThat(SORT.predicate(T1, null, params)).isEqualTo("TRUE");
        assertThat(params).isEmpty();
        assertThat(SORT.selectKeys()).isEqualTo("c.updated_at AS k0, CAST(c.id AS text) AS k1");
        assertThat(SORT.branchOrderBy()).isEqualTo("c.updated_at DESC, CAST(c.id AS text) DESC");
        assertThat(SORT.outerOrderBy()).isEqualTo("k0 DESC, k1 DESC, tenant_id COLLATE \"C\" DESC");
    }

    @Test
    @DisplayName("descending: a branch of a tenant sorting before the cursor tenant includes equal keys (<=)")
    void descendingTieBreak() {
        KeysetCursor cursor = new KeysetCursor("m", List.of("2026-11-08T09:00:00Z", "id"));
        List<Object> params = new ArrayList<>();

        assertThat(SORT.predicate("a", cursor, params))
                .isEqualTo("(c.updated_at, CAST(c.id AS text)) <= (CAST(? AS timestamptz), CAST(? AS text))");
        assertThat(SORT.predicate("m", cursor, params)).contains(") < (");
        assertThat(SORT.predicate("z", cursor, params)).contains(") < (");
        assertThat(params).hasSize(6);
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
    @DisplayName("merge comparator: keys desc, then tenant id desc — the SQL outer order")
    void mergeOrder() {
        Instant t = Instant.parse("2026-11-08T09:00:00Z");
        List<KeysetSort.Row<String>> rows = new ArrayList<>(List.of(
                new KeysetSort.Row<>("a-old", T1, List.of(t.minusSeconds(5), "b")),
                new KeysetSort.Row<>("b-tie", T2, List.of(t, "a")),
                new KeysetSort.Row<>("a-tie", T1, List.of(t, "a")),
                new KeysetSort.Row<>("a-new", T1, List.of(t, "z"))));

        rows.sort(SORT.comparator());

        assertThat(rows).extracting(KeysetSort.Row::item).containsExactly("a-new", "b-tie", "a-tie", "a-old");
        assertThat(SORT.format(List.of(t, "a"))).containsExactly("2026-11-08T09:00:00Z", "a");
    }

    @Test
    @DisplayName("union: one parenthesised branch per tenant")
    void union() {
        assertThat(CrossTenantSql.unionAll(tenants(2), t -> "SELECT 1 FROM " + t.schemaName() + ".x"))
                .isEqualTo("(SELECT 1 FROM kv_aaaaa0.x) UNION ALL (SELECT 1 FROM kv_aaaaa1.x)");
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
