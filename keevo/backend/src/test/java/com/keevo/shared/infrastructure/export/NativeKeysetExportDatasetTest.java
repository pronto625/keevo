package com.keevo.shared.infrastructure.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Export keyset helper (v1s-18-2)")
class NativeKeysetExportDatasetTest {

    private static final List<KeyType> TYPES = List.of(KeyType.TIMESTAMP, KeyType.DATE, KeyType.TEXT, KeyType.UUID);

    @Test
    @DisplayName("cursor round-trips typed values, text with separators included")
    void cursorRoundTrip() {
        Instant at = Instant.parse("2026-10-12T08:15:30.123456Z");
        LocalDate day = LocalDate.of(2026, 10, 12);
        UUID id = UUID.randomUUID();
        List<Object> values = List.of(at, day, "Épicerie | 50% . _x_", id);

        String cursor = KeysetCursor.encode(TYPES, values);

        assertThat(cursor).matches("[A-Za-z0-9_\\-.]+");
        assertThat(KeysetCursor.decode(TYPES, cursor)).containsExactly(at, day, "Épicerie | 50% . _x_", id);
        List<KeyType> textThenId = List.of(KeyType.TEXT, KeyType.UUID);
        assertThat(KeysetCursor.decode(textThenId, KeysetCursor.encode(textThenId, List.of("", id))))
                .containsExactly("", id); // COALESCE(name, '') keys
    }

    @Test
    @DisplayName("malformed cursor → IllegalArgumentException (garbage, arity, type, blank); null keyset value refused")
    void malformedCursor() {
        String twoParts = KeysetCursor.encode(List.of(KeyType.TEXT, KeyType.TEXT), List.of("a", "b"));
        for (String bad : List.of("", "%%%", "not-base64!", twoParts, "a".repeat(5000))) {
            assertThatThrownBy(() -> KeysetCursor.decode(List.of(KeyType.TIMESTAMP, KeyType.UUID), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        String textThenText = KeysetCursor.encode(List.of(KeyType.TEXT, KeyType.TEXT), List.of("yesterday", "x"));
        assertThatThrownBy(() -> KeysetCursor.decode(List.of(KeyType.TIMESTAMP, KeyType.UUID), textThenText))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeysetCursor.encode(List.of(KeyType.TEXT), Arrays.asList((Object) null)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Minimal provider over a mocked EntityManager. */
    private static final class Demo extends NativeKeysetExportDataset {
        Demo(EntityManager em) { super(em); }
        @Override public String key() { return "demo"; }
        @Override public String label() { return "Démo"; }
        @Override public List<ExportColumn> columns() {
            return List.of(new ExportColumn("name", "Nom", ExportColumnType.TEXT),
                    new ExportColumn("amount", "Montant", ExportColumnType.AMOUNT_XAF));
        }
        @Override protected String selectList(ExportFilter f) { return "d.name, d.amount"; }
        @Override protected String fromClause() { return "demo d"; }
        @Override protected String storePredicate() { return "d.store_id = CAST(:storeId AS uuid)"; }
        @Override protected String periodExpression() { return "d.at"; }
        @Override protected Map<String, String> criteriaPredicates() { return Map.of("kind", "d.kind = :crit_kind"); }
        @Override protected List<Key> keyset() {
            return List.of(new Key("d.at", KeyType.TIMESTAMP), new Key("d.day", KeyType.DATE),
                    new Key("COALESCE(d.name, '')", KeyType.TEXT), new Key("d.id", KeyType.UUID));
        }
        @Override protected List<Object> mapRow(Object[] r) {
            return Arrays.asList(ExportValues.str(r[0]), ExportValues.toLong(r[1]));
        }
    }

    @Test
    @DisplayName("readPage: limit+1 fetch, typed keyset parameters, next cursor from trailing key columns, filters bound")
    void readPageBindsTypedKeyset() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(em.createNativeQuery(sql.capture())).thenReturn(q);
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<Object[]> raw = new ArrayList<>();
        raw.add(new Object[]{"A", new BigDecimal("1500"), Timestamp.from(at), Date.valueOf("2026-10-01"), "A", id1});
        raw.add(new Object[]{"B", 7, Timestamp.from(at), Date.valueOf("2026-10-01"), "B", id2});
        when(q.getResultList()).thenReturn(raw);

        UUID store = UUID.randomUUID();
        ExportFilter filter = new ExportFilter(store, at.minusSeconds(60), at.plusSeconds(60), Map.of("kind", "X"));
        String after = KeysetCursor.encode(List.of(KeyType.TIMESTAMP, KeyType.DATE, KeyType.TEXT, KeyType.UUID),
                List.of(at.minusSeconds(1), LocalDate.of(2026, 9, 30), "Z", id1));

        ExportPage page = new Demo(em).readPage(filter, after, 1);

        assertThat(page.rows()).containsExactly(List.of("A", 1500L));
        assertThat(KeysetCursor.decode(TYPES, page.nextCursor()))
                .containsExactly(at, LocalDate.of(2026, 10, 1), "A", id1);
        assertThat(sql.getValue())
                .startsWith("SELECT d.name, d.amount, d.at, d.day, COALESCE(d.name, ''), d.id FROM demo d WHERE ")
                .contains("(d.store_id = CAST(:storeId AS uuid))", "d.at >= CAST(:periodFrom AS timestamptz)",
                        "d.at <= CAST(:periodTo AS timestamptz)", "(d.kind = :crit_kind)",
                        "(d.at, d.day, COALESCE(d.name, ''), d.id) > (CAST(:k0 AS timestamptz), CAST(:k1 AS date),"
                                + " CAST(:k2 AS text), CAST(:k3 AS uuid))")
                .endsWith(" ORDER BY d.at, d.day, COALESCE(d.name, ''), d.id");
        verify(q).setMaxResults(2);
        verify(q).setParameter("k0", Timestamp.from(at.minusSeconds(1)));
        verify(q).setParameter("k1", Date.valueOf("2026-09-30"));
        verify(q).setParameter("k2", "Z");
        verify(q).setParameter("k3", id1.toString());
        verify(q).setParameter("storeId", store.toString());
        verify(q).setParameter("crit_kind", "X");
    }

    @Test
    @DisplayName("last page → null cursor; no filter → no WHERE; malformed cursor → IllegalArgumentException")
    void lastPageAndMalformed() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(em.createNativeQuery(sql.capture())).thenReturn(q);
        List<Object[]> raw = new ArrayList<>();
        raw.add(new Object[]{"A", 1L, Timestamp.from(Instant.now()), Date.valueOf("2026-10-01"), "A", UUID.randomUUID()});
        when(q.getResultList()).thenReturn(raw);

        ExportPage page = new Demo(em).readPage(ExportFilter.none(), null, 5);

        assertThat(page.nextCursor()).isNull();
        assertThat(sql.getValue()).doesNotContain("WHERE");
        assertThatThrownBy(() -> new Demo(em).readPage(ExportFilter.none(), "garbage", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("filter metadata follows the declared predicates")
    void metadata() {
        Demo d = new Demo(mock(EntityManager.class));
        assertThat(d.supportsStoreFilter()).isTrue();
        assertThat(d.supportsPeriodFilter()).isTrue();
        assertThat(d.criteriaKeys()).containsExactly("kind");
    }

    @Test
    @DisplayName("converters: numbers → Long, dates, labels with raw-code fallback")
    void converters() {
        assertThat(ExportValues.toLong(new BigDecimal("12.5"))).isEqualTo(13L);
        assertThat(ExportValues.toLong(3)).isEqualTo(3L);
        assertThat(ExportValues.toLocalDate(Date.valueOf("2026-10-12"))).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ExportValues.frLabel(Map.of("CASH", "Espèces"), "CASH")).isEqualTo("Espèces");
        assertThat(ExportValues.frLabel(Map.of("CASH", "Espèces"), "BARTER")).isEqualTo("BARTER");
        assertThat(ExportValues.frLabel(Map.of(), null)).isNull();
    }
}
