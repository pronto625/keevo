package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9b — {@code public.web_push_subscriptions} on PostgreSQL (V47): atomic upsert rule, ANY queries. */
@DisplayName("Web Push subscriptions on PostgreSQL (Story 19.9b)")
class WebPushSubscriptionRepositoryPostgresTest {

    private static final String TEST_DB = "keevo_web_push_subscriptions";
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/abc";

    private static JdbcTemplate jdbc;
    private JdbcWebPushSubscriptionRepository repository;

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
        jdbc.update("DELETE FROM public.web_push_subscriptions");
        repository = new JdbcWebPushSubscriptionRepository(jdbc);
    }

    private static WebPushSubscription sub(UUID user, String endpoint, String p256dh, String auth) {
        return new WebPushSubscription(UUID.randomUUID(), user, endpoint, p256dh, auth, "UA");
    }

    @Test
    @DisplayName("new endpoint inserted; same user again updates the keys (one row)")
    void insertThenUpdate() {
        UUID user = UUID.randomUUID();
        assertThat(repository.upsert(sub(user, FCM, "k1", "a1"))).isTrue();
        assertThat(repository.upsert(sub(user, FCM, "k2", "a2"))).isTrue();
        List<WebPushSubscription> rows = repository.findByUserIds(List.of(user));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).p256dh()).isEqualTo("k2");
    }

    @Test
    @DisplayName("endpoint of another user: reassigned only with identical keys")
    void reassignRule() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        repository.upsert(sub(owner, FCM, "k1", "a1"));

        assertThat(repository.upsert(sub(other, FCM, "kX", "aX"))).isFalse();
        assertThat(repository.findByUserIds(List.of(owner))).hasSize(1);

        assertThat(repository.upsert(sub(other, FCM, "k1", "a1"))).isTrue();
        assertThat(repository.findByUserIds(List.of(owner))).isEmpty();
        assertThat(repository.findByUserIds(List.of(other))).hasSize(1);
    }

    @Test
    @DisplayName("findByUserIds in one query; deleteByIds and deleteByUserAndEndpoint scoped")
    void findAndDelete() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        repository.upsert(sub(a, FCM + "1", "k", "a"));
        repository.upsert(sub(a, FCM + "2", "k", "a"));
        repository.upsert(sub(b, FCM + "3", "k", "a"));
        List<WebPushSubscription> both = repository.findByUserIds(List.of(a, b));
        assertThat(both).hasSize(3);

        assertThat(repository.deleteByUserAndEndpoint(b, FCM + "1")).isZero();
        assertThat(repository.deleteByUserAndEndpoint(a, FCM + "1")).isOne();

        List<UUID> ids = repository.findByUserIds(List.of(a, b)).stream().map(WebPushSubscription::id).toList();
        assertThat(repository.deleteByIds(ids)).isEqualTo(2);
        assertThat(repository.findByUserIds(List.of(a, b))).isEmpty();
        assertThat(repository.findByUserIds(List.of())).isEmpty();
    }
}
