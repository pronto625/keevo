package com.keevo.shared.infrastructure.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * EventPublicationMaintenance — read/purge queries on {@code public.event_publication} that the Spring
 * Modulith 1.4 API does not offer (counts, ages, deleted row counts) — Story 12.4. Plain autocommit JDBC on
 * the public table (never tenant-routed).
 */
@Component
public class EventPublicationMaintenance {

    /** Incomplete publications: how many, and the publication date of the oldest ({@code null} if none). */
    public record IncompleteStats(long count, Instant oldest) {}

    private final JdbcTemplate jdbc;

    public EventPublicationMaintenance(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public IncompleteStats incompleteStats() {
        return jdbc.queryForObject(
                "SELECT COUNT(*), MIN(publication_date) FROM public.event_publication WHERE completion_date IS NULL",
                (rs, i) -> {
                    Timestamp oldest = rs.getTimestamp(2);
                    return new IncompleteStats(rs.getLong(1), oldest == null ? null : oldest.toInstant());
                });
    }

    /** Distinct listener ids of the incomplete publications published before {@code before}. */
    public List<String> incompleteListenerIdsPublishedBefore(Instant before) {
        return jdbc.queryForList("SELECT DISTINCT listener_id FROM public.event_publication "
                + "WHERE completion_date IS NULL AND publication_date < ? ORDER BY listener_id",
                String.class, Timestamp.from(before));
    }

    /** @return number of completed publications deleted (completed before {@code before}). */
    public int deleteCompletedBefore(Instant before) {
        return jdbc.update("DELETE FROM public.event_publication WHERE completion_date IS NOT NULL "
                + "AND completion_date < ?", Timestamp.from(before));
    }
}
