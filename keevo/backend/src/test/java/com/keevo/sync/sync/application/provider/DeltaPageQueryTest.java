package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeltaPageQueryTest {

    @Mock private EntityManager em;
    @Mock private Query query;

    private static final Instant SINCE = Instant.parse("2026-03-20T10:00:00Z");

    private static Object[] row(String id, Instant cursor) {
        return new Object[]{id, "name-" + id, Timestamp.from(cursor)};
    }

    private String capturedSql() {
        var sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    void firstPage_overlapsSinceAndUsesAStableOrder() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        var result = DeltaPageQuery.fetch(em, "id, name", "products", "updated_at", SINCE, null, 1000);

        String sql = capturedSql();
        assertThat(sql).contains("WHERE updated_at >= :since");
        assertThat(sql).contains("ORDER BY updated_at, CAST(id AS text)");
        assertThat(sql).doesNotContain(":afterId");
        verify(query).setParameter("since", Timestamp.from(SINCE));
        verify(query).setMaxResults(1001);
        assertThat(result.next()).isNull();
    }

    @Test
    void fullPage_returnsThePositionOfItsLastRow() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        var t1 = SINCE.plusSeconds(1);
        var t2 = SINCE.plusSeconds(2);
        List<Object[]> rows = new ArrayList<>(List.of(row("a", t1), row("b", t2), row("c", t2)));
        when(query.getResultList()).thenReturn(rows);

        var result = DeltaPageQuery.fetch(em, "id, name", "products", "updated_at", SINCE, null, 2);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.next()).isEqualTo(new Position(t2, "b"));
    }

    @Test
    void lastPage_hasNoNextPosition() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        List<Object[]> rows = new ArrayList<>(List.<Object[]>of(row("a", SINCE), row("b", SINCE)));
        when(query.getResultList()).thenReturn(rows);

        var result = DeltaPageQuery.fetch(em, "id, name", "products", "updated_at", SINCE, null, 2);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.next()).isNull();
    }

    // Many rows can share one timestamp (bulk import in one transaction): the id breaks the tie.
    @Test
    void nextPage_resumesStrictlyAfterThePreviousRow() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        var after = new Position(SINCE.plusSeconds(5), "b");

        DeltaPageQuery.fetch(em, "id, name", "products", "updated_at", SINCE, after, 1000);

        assertThat(capturedSql()).contains(
                "AND (updated_at, CAST(id AS text)) > (:afterCursor, :afterId)");
        verify(query).setParameter("afterCursor", Timestamp.from(after.cursor()));
        verify(query).setParameter("afterId", "b");
    }
}
