package com.keevo.crm.contact.adapter.in.bootstrap;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Story 20.2 AC — after the backfill every existing client has its {@code phone_normalized}; {@code store_id} stays
 * NULL and {@code updated_at} is not bumped; a second run changes nothing; the done-marker stops later runs.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ClientPhoneBackfillJob — téléphone normalisé des clients existants (20.2, PostgreSQL)")
class ClientPhoneBackfillJobPostgresTest extends FullContextIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final Timestamp OLD = Timestamp.from(Instant.parse("2026-01-01T08:00:00Z"));

    private String schema;
    private ClientPhoneBackfillJob job;

    @BeforeEach
    void setUp() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        job = new ClientPhoneBackfillJob(jdbc, transactionManager, mock(LockProvider.class), false);
    }

    /** A pre-20.2 row: no store, no normalized phone. */
    private UUID legacy(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone, created_at, updated_at) "
                + "VALUES (?, 'Client', ?, ?, ?)", id, phone, OLD, OLD);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT phone_normalized, store_id, updated_at FROM \"" + schema
                + "\".clients WHERE id = ?", id);
    }

    @Test
    @DisplayName("E.164 rows set-based, drifted rows in Java pages, invalid stays NULL; store untouched; rerun no-op")
    void backfill_is_complete_and_idempotent() {
        int count = ClientPhoneBackfillJob.BATCH + 7;
        for (int i = 0; i < count; i++) legacy(String.format("+237699%06d", i));
        UUID e164 = legacy("+237677000111");
        UUID spaced = legacy("677 00 01 12");          // drifted: no '+', separators
        UUID doubleZero = legacy("0033612345678");
        UUID broken = legacy("n/a");

        int changed = job.backfillTenant(schema);

        assertThat(changed).isEqualTo(count + 3);
        assertThat(row(e164).get("phone_normalized")).isEqualTo("+237677000111");
        assertThat(row(spaced).get("phone_normalized")).isEqualTo("+237677000112");
        assertThat(row(doubleZero).get("phone_normalized")).isEqualTo("+33612345678");
        assertThat(row(broken).get("phone_normalized")).isNull();
        assertThat(row(e164).get("store_id")).isNull();
        assertThat(row(e164).get("updated_at")).isEqualTo(OLD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE phone_normalized IS NULL",
                Integer.class)).isEqualTo(1);

        assertThat(job.backfillTenant(schema)).isZero();
    }

    @Test
    @DisplayName("passe complète : marqueur posé, la passe suivante ne lit plus aucun compte")
    void marker_stops_later_runs() {
        UUID id = legacy("+237699999001");
        jdbc.update("DELETE FROM public.data_migrations WHERE name = ?", ClientPhoneBackfillJob.MARKER);

        job.backfillAll();

        assertThat(row(id).get("phone_normalized")).isEqualTo("+237699999001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.data_migrations WHERE name = ?", Integer.class,
                ClientPhoneBackfillJob.MARKER)).isEqualTo(1);
        UUID later = legacy("+237699999002");
        assertThat(job.backfillAll()).isZero();
        assertThat(row(later).get("phone_normalized")).isNull();
    }

    @Test
    @DisplayName("verrou détenu ailleurs → rien ne tourne")
    void lock_held_elsewhere() {
        LockProvider locks = mock(LockProvider.class);
        when(locks.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        JdbcTemplate spyJdbc = spy(jdbc);
        new ClientPhoneBackfillJob(spyJdbc, transactionManager, locks, true).runAtStartup();
        verifyNoInteractions(spyJdbc);
    }

    @Test
    @DisplayName("parité : la regex SQL de la passe ensembliste est celle de PhoneNumbers (même verdict sur PostgreSQL)")
    void sqlRegexIsTheJavaE164Pattern() {
        assertThat(ClientPhoneBackfillJob.E164_SQL).contains("'" + com.keevo.shared.domain.PhoneNumbers.E164_REGEX + "'");
        for (String phone : java.util.List.of("+237699001122", "+33612345678", "+12345678", "+123456789012345",
                "+1234567", "+1234567890123456", "+0123456789", "237699001122", "+237 699", "+23769900112a")) {
            Boolean sql = jdbc.queryForObject("SELECT ? ~ '" + com.keevo.shared.domain.PhoneNumbers.E164_REGEX + "'",
                    Boolean.class, phone);
            boolean java = phone.equals(com.keevo.shared.domain.PhoneNumbers.toE164(phone, null));
            assertThat(sql).as(phone).isEqualTo(java);
        }
    }
}
