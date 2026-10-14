package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanExpiryReminderService (Story 9-2)")
class PlanExpiryReminderServiceTest {

    /** 2026-10-13 08:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-10-13T07:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();
    private static final String S1 = "kv_aaaaa1";
    private static final String S2 = "kv_bbbbb2";
    private static final String PHONE = "+237600000001";

    @Mock JdbcTemplate jdbc;
    @Mock WhatsAppPort whatsApp;
    @Mock OwnerRecipientResolver resolver;

    PlanExpiryReminderService service;

    @BeforeEach
    void setUp() {
        service = new PlanExpiryReminderService(jdbc, whatsApp, resolver, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void tenants(Object... idSchemaPairs) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < idSchemaPairs.length; i += 2) {
            rows.add(Map.of("id", idSchemaPairs[i], "schema_name", idSchemaPairs[i + 1]));
        }
        when(jdbc.queryForList(PlanExpiryReminderService.FETCH_TENANTS)).thenReturn(rows);
    }

    private void expiring(String schema, String plan, int days) {
        Instant expiry = TODAY.plusDays(days).atTime(LocalTime.of(23, 59, 59))
                .atZone(com.keevo.shared.domain.time.KeevoZones.WAT).toInstant();
        when(jdbc.queryForList(contains("\"" + schema + "\"")))
                .thenReturn(List.of(Map.of("plan_type", plan, "expires_at", Timestamp.from(expiry))));
    }

    private void claim(UUID tenant, int threshold, int days, int inserted) {
        when(jdbc.update(PlanExpiryReminderService.CLAIM_SQL, tenant, threshold, TODAY.plusDays(days))).thenReturn(inserted);
    }

    @Test
    @DisplayName("thresholds: 7 when 1 < daysLeft ≤ 7, 1 when 0 ≤ daysLeft ≤ 1, none otherwise")
    void thresholds() {
        assertThat(PlanExpiryReminderService.thresholdFor(8)).isNull();
        assertThat(PlanExpiryReminderService.thresholdFor(7)).isEqualTo(7);
        assertThat(PlanExpiryReminderService.thresholdFor(5)).isEqualTo(7); // missed run still reminds
        assertThat(PlanExpiryReminderService.thresholdFor(2)).isEqualTo(7);
        assertThat(PlanExpiryReminderService.thresholdFor(1)).isEqualTo(1);
        assertThat(PlanExpiryReminderService.thresholdFor(0)).isEqualTo(1);
        assertThat(PlanExpiryReminderService.thresholdFor(-1)).isNull();
    }

    @Test
    @DisplayName("PREMIUM expiring in 7 WAT days → claimed and sent once")
    void sevenDays_sent() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM", 7);
        claim(T1, 7, 7, 1);
        when(resolver.resolveOwnerWhatsAppPhone(S1)).thenReturn(Optional.of(PHONE));

        assertThat(service.execute()).isEqualTo(1);

        verify(whatsApp).sendReport(PHONE,
                "⏰ Votre abonnement Keevo Premium expire le 20/10/2026. Contactez-nous pour renouveler.");
        verify(jdbc, never()).update(eq(PlanExpiryReminderService.UNCLAIM_SQL), any(), any(), any());
    }

    @Test
    @DisplayName("missed runs: 4 days left still claims threshold 7, 0 days left claims threshold 1")
    void missedRun_stillReminds() {
        tenants(T1, S1, T2, S2);
        expiring(S1, "PREMIUM", 4);
        expiring(S2, "PREMIUM", 0);
        claim(T1, 7, 4, 1);
        claim(T2, 1, 0, 1);
        when(resolver.resolveOwnerWhatsAppPhone(anyString())).thenReturn(Optional.of(PHONE));

        assertThat(service.execute()).isEqualTo(2);
        verify(whatsApp).sendReport(eq(PHONE), contains("17/10/2026"));
        verify(whatsApp).sendReport(eq(PHONE), contains("13/10/2026"));
    }

    @Test
    @DisplayName("PREMIUM_TRIAL gets the trial text")
    void trial_text() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM_TRIAL", 1);
        claim(T1, 1, 1, 1);
        when(resolver.resolveOwnerWhatsAppPhone(S1)).thenReturn(Optional.of(PHONE));

        service.execute();

        verify(whatsApp).sendReport(PHONE, "⏰ Votre essai Keevo Premium se termine le 14/10/2026. "
                + "Contactez-nous pour passer au Premium et garder toutes les fonctionnalités.");
    }

    @Test
    @DisplayName("second run: claim already taken → nothing sent")
    void secondRun_idempotent() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM", 7);
        claim(T1, 7, 7, 0);

        assertThat(service.execute()).isZero();

        verifyNoInteractions(whatsApp, resolver);
    }

    @Test
    @DisplayName("8 days left → no claim, no send")
    void outsideWindow_ignored() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM", 8);

        service.execute();

        verify(jdbc, never()).update(anyString(), any(), any(), any());
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("owner phone missing → claim released so the next run retries")
    void noPhone_unclaimed() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM", 7);
        claim(T1, 7, 7, 1);
        when(resolver.resolveOwnerWhatsAppPhone(S1)).thenReturn(Optional.empty());

        assertThat(service.execute()).isZero();

        verifyNoInteractions(whatsApp);
        verify(jdbc).update(PlanExpiryReminderService.UNCLAIM_SQL, T1, 7, TODAY.plusDays(7));
    }

    @Test
    @DisplayName("WhatsApp down → claim released, the run completes")
    void sendFailure_unclaimed() {
        tenants(T1, S1);
        expiring(S1, "PREMIUM", 7);
        claim(T1, 7, 7, 1);
        when(resolver.resolveOwnerWhatsAppPhone(S1)).thenReturn(Optional.of(PHONE));
        doThrow(new RuntimeException("Wassender down")).when(whatsApp).sendReport(anyString(), anyString());

        assertThat(service.execute()).isZero();
        verify(jdbc).update(PlanExpiryReminderService.UNCLAIM_SQL, T1, 7, TODAY.plusDays(7));
    }

    @Test
    @DisplayName("SQL/data error of one tenant (schema or claim) → that tenant skipped, the next one reminded")
    void tenantError_skipped() {
        tenants(T1, S1, T2, S2);
        when(jdbc.queryForList(contains("\"" + S1 + "\"")))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("missing")));
        expiring(S2, "PREMIUM", 7);
        when(jdbc.update(PlanExpiryReminderService.CLAIM_SQL, T2, 7, TODAY.plusDays(7)))
                .thenThrow(new DataIntegrityViolationException("fk"));

        assertThat(service.execute()).isZero();
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("connection failure → rethrown, the run stops")
    void connectionFailure_rethrown() {
        tenants(T1, S1);
        when(jdbc.queryForList(contains("\"" + S1 + "\""))).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThatThrownBy(() -> service.execute()).isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    @Test
    @DisplayName("only ACTIVE PREMIUM / PREMIUM_TRIAL rows of live non-FREE tenants are considered")
    void sqlShape() {
        assertThat(PlanExpiryReminderService.EXPIRY_SQL)
                .contains("plan_type IN ('PREMIUM', 'PREMIUM_TRIAL')").contains("status = 'ACTIVE'");
        assertThat(PlanExpiryReminderService.FETCH_TENANTS).contains("'DELETED'").contains("'DELETION_PENDING'")
                .contains("plan_type <> 'FREE'");
        assertThat(PlanExpiryReminderService.CLAIM_SQL).contains("ON CONFLICT");
    }
}
