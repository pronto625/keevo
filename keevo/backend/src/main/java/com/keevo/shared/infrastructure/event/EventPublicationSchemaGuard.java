package com.keevo.shared.infrastructure.event;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * EventPublicationSchemaGuard — fail-fast at boot if the event registry tables are missing (Story 12.4).
 *
 * <p>Schema initialisation of Spring Modulith is disabled (Flyway V28 owns the tables). Without this
 * check a missing {@code public.event_publication} would only surface on the first business transaction
 * that publishes an event — and roll it back. Runs after Flyway ({@link DependsOnDatabaseInitialization}).
 */
@Component
@DependsOnDatabaseInitialization
public class EventPublicationSchemaGuard implements InitializingBean {

    static final String[] REQUIRED_TABLES = {"event_publication", "event_listener_receipt"};

    private final JdbcTemplate jdbc;

    public EventPublicationSchemaGuard(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        for (String table : REQUIRED_TABLES) {
            Integer found = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            if (found == null || found == 0) {
                throw new IllegalStateException("[FailFast] Table public." + table
                        + " is missing — the event publication registry cannot work (Flyway V28 not applied?)");
            }
        }
    }
}
