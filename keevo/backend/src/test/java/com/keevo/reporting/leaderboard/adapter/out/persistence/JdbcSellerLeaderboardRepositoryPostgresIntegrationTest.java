package com.keevo.reporting.leaderboard.adapter.out.persistence;

import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository.RawSellerRow;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1s-14-2 — the seller leaderboard SQL against a real PostgreSQL (public baseline has sales,
 * employees and users). The shared database may hold rows of other tests, so assertions only
 * look at this test's sellers/stores.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("JdbcSellerLeaderboardRepository on PostgreSQL")
class JdbcSellerLeaderboardRepositoryPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private JdbcSellerLeaderboardRepository repo;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    /** 12:00 WAT on DAY. */
    private static final Instant NOON = Instant.parse("2026-10-12T11:00:00Z");

    private final UUID store = UUID.randomUUID();
    private final UUID otherStore = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = new JdbcSellerLeaderboardRepository(jdbc);
    }

    @Test
    @DisplayName("only COMPLETED sales count; CANCELLED excluded")
    void cancelledExcluded() {
        UUID awa = employee("Awa", "Ndiaye", store, "ACTIVE");
        sale(awa, store, NOON, 5_000, "COMPLETED");
        sale(awa, store, NOON, 9_000, "CANCELLED");

        var row = byId(repo.findRawSellers(DAY, DAY, store)).get(awa);
        assertThat(row.salesCount()).isEqualTo(1);
        assertThat(row.totalRevenue()).isEqualTo(5_000);
        assertThat(row.sellerName()).isEqualTo("Awa Ndiaye");
        assertThat(row.owner()).isFalse();
    }

    @Test
    @DisplayName("WAT boundary: 23:30 UTC on D-1 is 00:30 WAT on D")
    void watBoundary() {
        UUID awa = employee("Awa", "Ndiaye", store, "ACTIVE");
        sale(awa, store, Instant.parse("2026-10-11T23:30:00Z"), 4_000, "COMPLETED");

        assertThat(byId(repo.findRawSellers(DAY, DAY, store)).get(awa).salesCount()).isEqualTo(1);
        assertThat(byId(repo.findRawSellers(DAY.minusDays(1), DAY.minusDays(1), store)).get(awa).salesCount())
                .as("idle on D-1").isZero();
        // 23:30 WAT on D (22:30 UTC) is still D; 00:00 WAT on D+1 is not.
        sale(awa, store, Instant.parse("2026-10-12T22:30:00Z"), 1_000, "COMPLETED");
        sale(awa, store, Instant.parse("2026-10-12T23:00:00Z"), 7_000, "COMPLETED");
        var row = byId(repo.findRawSellers(DAY, DAY, store)).get(awa);
        assertThat(row.salesCount()).isEqualTo(2);
        assertThat(row.totalRevenue()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("store scope: only that store's sales and active employees")
    void storeScope() {
        UUID awa = employee("Awa", "Ndiaye", store, "ACTIVE");
        UUID bello = employee("Bello", "Diallo", otherStore, "ACTIVE");
        sale(awa, store, NOON, 3_000, "COMPLETED");
        sale(awa, otherStore, NOON, 8_000, "COMPLETED");
        sale(bello, otherStore, NOON, 2_000, "COMPLETED");

        var scoped = byId(repo.findRawSellers(DAY, DAY, store));
        assertThat(scoped).containsOnlyKeys(awa);
        assertThat(scoped.get(awa).totalRevenue()).isEqualTo(3_000);

        var all = byId(repo.findRawSellers(DAY, DAY, null));
        assertThat(all.get(awa).totalRevenue()).isEqualTo(11_000);
        assertThat(all.get(awa).salesCount()).isEqualTo(2);
        assertThat(all.get(bello).totalRevenue()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("idle active employee → 0/0; inactive employee without sale omitted")
    void idleEmployee() {
        UUID idle = employee("Chantal", "Mbarga", store, "ACTIVE");
        UUID gone = employee("Denis", "Fotso", store, "INACTIVE");

        var rows = byId(repo.findRawSellers(DAY, DAY, store));
        assertThat(rows).containsOnlyKeys(idle);
        assertThat(rows.get(idle).salesCount()).isZero();
        assertThat(rows.get(idle).totalRevenue()).isZero();
        assertThat(rows.get(idle).sellerName()).isEqualTo("Chantal Mbarga");
        assertThat(rows).doesNotContainKey(gone);
    }

    @Test
    @DisplayName("owner who sold → « Propriétaire », owner flag; unknown user → « Vendeur inconnu »")
    void ownerAndUnknownLabels() {
        UUID owner = user("OWNER");
        UUID ghost = UUID.randomUUID();
        sale(owner, store, NOON, 6_000, "COMPLETED");
        sale(ghost, store, NOON, 1_000, "COMPLETED");

        var rows = byId(repo.findRawSellers(DAY, DAY, store));
        assertThat(rows.get(owner).sellerName()).isEqualTo("Propriétaire");
        assertThat(rows.get(owner).owner()).isTrue();
        assertThat(rows.get(ghost).sellerName()).isEqualTo("Vendeur inconnu");
        assertThat(rows.get(ghost).owner()).isFalse();
    }

    @Test
    @DisplayName("no sale, no employee → empty")
    void empty() {
        assertThat(repo.findRawSellers(DAY, DAY, UUID.randomUUID())).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Map<UUID, RawSellerRow> byId(List<RawSellerRow> rows) {
        return rows.stream().collect(Collectors.toMap(RawSellerRow::sellerId, Function.identity()));
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        String phone = "+2376" + ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L);
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                        + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'x', ?, ?)",
                id, phone, role);
        return id;
    }

    private UUID employee(String first, String last, UUID storeId, String status) {
        UUID userId = user("EMPLOYEE");
        jdbc.update("INSERT INTO employees (id, created_at, updated_at, first_name, last_name, "
                        + "password_change_required, status, store_id, user_id) VALUES (?, now(), now(), ?, ?, false, ?, ?, ?)",
                UUID.randomUUID(), first, last, status, storeId, userId);
        return userId;
    }

    private void sale(UUID sellerUserId, UUID storeId, Instant occurredAt, int amount, String status) {
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, 0)",
                UUID.randomUUID(), Timestamp.from(occurredAt), sellerUserId, Timestamp.from(occurredAt),
                status, storeId, amount);
    }
}
