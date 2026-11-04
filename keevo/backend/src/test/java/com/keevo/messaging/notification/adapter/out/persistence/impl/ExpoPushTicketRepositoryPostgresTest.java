package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.model.ExpoPushTicket;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9b — {@code public.expo_push_tickets} on PostgreSQL (V46): batch insert, keyset read, deletes. */
@DisplayName("Expo push tickets on PostgreSQL (Story 19.9b)")
class ExpoPushTicketRepositoryPostgresTest {

    private static final String TEST_DB = "keevo_expo_push_tickets";

    private static JdbcTemplate jdbc;
    private JdbcExpoPushTicketRepository repository;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.expo_push_tickets");
        repository = new JdbcExpoPushTicketRepository(jdbc);
    }

    private void age(String id, Instant createdAt) {
        jdbc.update("UPDATE public.expo_push_tickets SET created_at = ? WHERE ticket_id = ?",
                Timestamp.from(createdAt), id);
    }

    @Test
    @DisplayName("saveAll: one batch, duplicates ignored, invalid schema stores nothing")
    void save() {
        Map<String, String> tickets = new LinkedHashMap<>();
        tickets.put("t1", "ExponentPushToken[a]");
        tickets.put("t2", "ExponentPushToken[b]");
        repository.saveAll("kv_abc123", tickets);
        repository.saveAll("kv_abc123", Map.of("t1", "ExponentPushToken[a]"));
        repository.saveAll("public", Map.of("t9", "ExponentPushToken[z]"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.expo_push_tickets", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("keyset read in (created_at, ticket_id) order; deleteByIds; purge")
    void readAndDelete() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);
        Map<String, String> tickets = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c", "d")) tickets.put(id, "ExponentPushToken[" + id + "]");
        repository.saveAll("kv_abc123", tickets);
        age("a", base);
        age("b", base);
        age("c", base.plusSeconds(1));
        age("d", base.minus(2, ChronoUnit.DAYS));

        Instant before = Instant.now().minus(15, ChronoUnit.MINUTES);
        List<ExpoPushTicket> first = repository.findCreatedBefore(before, null, null, 2);
        assertThat(first).extracting(ExpoPushTicket::ticketId).containsExactly("d", "a");
        assertThat(first.get(1).tenantSchema()).isEqualTo("kv_abc123");
        ExpoPushTicket last = first.get(1);
        assertThat(repository.findCreatedBefore(before, last.createdAt(), last.ticketId(), 2))
                .extracting(ExpoPushTicket::ticketId).containsExactly("b", "c");

        assertThat(repository.purgeCreatedBefore(Instant.now().minus(1, ChronoUnit.DAYS))).isOne();
        assertThat(repository.deleteByIds(List.of("a", "b", "zz"))).isEqualTo(2);
        assertThat(repository.findCreatedBefore(before, null, null, 10)).extracting(ExpoPushTicket::ticketId)
                .containsExactly("c");
    }
}
