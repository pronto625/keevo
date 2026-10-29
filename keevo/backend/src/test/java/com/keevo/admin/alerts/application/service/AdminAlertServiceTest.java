package com.keevo.admin.alerts.application.service;

import com.keevo.admin.alerts.domain.model.AdminAlert;
import com.keevo.admin.alerts.domain.model.AlertSeverity;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.out.AlertAckRepository;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.admin.platform.domain.model.SystemHealth;
import com.keevo.admin.platform.domain.model.SystemHealthFixtures;
import com.keevo.admin.platform.domain.port.in.GetSystemHealthUseCase;
import com.keevo.admin.subscription.domain.model.PlanAlert;
import com.keevo.admin.subscription.domain.port.in.ListPlanAlertsUseCase;
import com.keevo.feedback.api.FeedbackInboxPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAlertService (Story 9-5)")
class AdminAlertServiceTest {

    /** 11:00 WAT. */
    static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID F1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Mock AlertSourcesPort sources;
    @Mock AlertAckRepository acks;
    @Mock ListPlanAlertsUseCase planAlerts;
    @Mock GetSystemHealthUseCase health;
    @Mock FeedbackInboxPort feedbackInbox;

    MutableClock clock;
    AdminAlertService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new AdminAlertService(sources, acks, planAlerts, health, feedbackInbox, clock);
        lenient().when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW).build());
        lenient().when(sources.unhandledHighFeedbackSince(any())).thenReturn(List.of());
        lenient().when(sources.deletionPending()).thenReturn(List.of());
        lenient().when(sources.expiredPlansNotFree(any())).thenReturn(List.of());
        lenient().when(sources.reportFailuresSince(any())).thenReturn(new ReportFailures(0, 0, null));
        lenient().when(sources.silentTenants(any())).thenReturn(List.of());
        lenient().when(planAlerts.listAlerts()).thenReturn(List.of());
        lenient().when(acks.handledAt(anyCollection())).thenReturn(Map.of());
    }

    /** Each call computes afresh (the feed is cached 30 s). */
    private List<AdminAlert> fresh(boolean includeHandled) {
        clock.advance(Duration.ofSeconds(31));
        return service.listAlerts(includeHandled);
    }

    private static void assertNoIds(AdminAlert a) {
        assertThat(a.title()).doesNotContainPattern(UUID_PATTERN);
        assertThat(a.description()).doesNotContainPattern(UUID_PATTERN).doesNotContain("kv_")
                .doesNotContain("PREMIUM").doesNotContain("DELETION_PENDING").doesNotContain("tenant");
    }

    @Test
    @DisplayName("healthy platform with nothing pending: empty feed")
    void empty() {
        assertThat(service.listAlerts(false)).isEmpty();
    }

    @Test
    @DisplayName("HIGH feedback of the last 30 days → HIGH alert keyed by the feedback id")
    void feedback() {
        when(sources.unhandledHighFeedbackSince(NOW.minus(Duration.ofDays(30)))).thenReturn(List.of(
                new HighFeedback(F1, "Boutique Awa", "bug", "Le scanner plante", NOW.minusSeconds(3600))));

        AdminAlert a = service.listAlerts(false).get(0);

        assertThat(a.key()).isEqualTo("feedback:" + F1);
        assertThat(a.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(a.title()).isEqualTo("Retour utilisateur prioritaire");
        assertThat(a.description()).isEqualTo("Boutique Awa — Problème signalé : « Le scanner plante »");
        assertThat(a.occurredAt()).isEqualTo(NOW.minusSeconds(3600));
        assertNoIds(a);
    }

    @Test
    @DisplayName("long feedback is cut on code points, never inside a surrogate pair")
    void feedbackExcerpt() {
        assertThat(AdminAlertService.excerpt("x".repeat(500))).hasSize(160).endsWith("…");
        String emojis = "😀".repeat(200);
        String cut = AdminAlertService.excerpt(emojis);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(160);
        assertThat(cut).isEqualTo("😀".repeat(159) + "…");
        assertThat(AdminAlertService.excerpt("  court  ")).isEqualTo("court");
    }

    @Test
    @DisplayName("DELETION_PENDING → MEDIUM with the days left; null date and due today")
    void deletion() {
        Instant scheduled = NOW.plus(Duration.ofDays(12)).minusSeconds(3600);
        when(sources.deletionPending()).thenReturn(List.of(
                new DeletionPending(T1, "Boutique Awa", scheduled),
                new DeletionPending(T2, "Boutique Ndiaye", null),
                new DeletionPending(F1, "Boutique Kane", NOW.minusSeconds(60))));

        Map<String, AdminAlert> byKey = new HashMap<>();
        service.listAlerts(false).forEach(a -> byKey.put(a.key(), a));

        AdminAlert a = byKey.get("deletion:" + T1 + ":2026-10-25");
        assertThat(a.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(a.description()).isEqualTo("Boutique Awa — suppression définitive dans 12 jours (le 25/10/2026)");
        assertThat(a.occurredAt()).isEqualTo(scheduled.minus(Duration.ofDays(30)));
        assertNoIds(a);
        assertThat(byKey.get("deletion:" + T2 + ":pending").description())
                .isEqualTo("Boutique Ndiaye — suppression du compte demandée");
        assertThat(byKey.get("deletion:" + F1 + ":2026-10-13").description())
                .isEqualTo("Boutique Kane — suppression définitive imminente");
    }

    @Test
    @DisplayName("expired but still not FREE beyond one missed nightly run (26 h) → HIGH integrity alert")
    void expiredNotFree() {
        Instant expired = Instant.parse("2026-10-11T22:59:59Z"); // 11/10 23:59:59 WAT
        when(sources.expiredPlansNotFree(NOW.minus(Duration.ofHours(26))))
                .thenReturn(List.of(new ExpiredPlan(T1, "Boutique Awa", "PREMIUM", expired)));

        AdminAlert a = service.listAlerts(false).get(0);

        assertThat(a.key()).isEqualTo("plan-expired:" + T1 + ":2026-10-11");
        assertThat(a.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(a.description()).isEqualTo(
                "Boutique Awa — abonnement Premium expiré le 11/10/2026 mais pas encore repassé au plan Gratuit");
        assertNoIds(a);
    }

    @Test
    @DisplayName("Premium / trial expiring within 7 days → LOW, occurredAt never in the future; expired 9-2 alerts not repeated")
    void expiring() {
        Instant expiresAt = Instant.parse("2026-10-16T22:59:59Z");
        Instant farther = Instant.parse("2026-10-21T22:59:59Z"); // expiresAt − 7 d is after now
        when(planAlerts.listAlerts()).thenReturn(List.of(
                new PlanAlert(T1, "Boutique Awa", PlanAlert.Kind.EXPIRING, "PREMIUM_TRIAL", 3, expiresAt, null),
                new PlanAlert(F1, "Boutique Kane", PlanAlert.Kind.EXPIRING, "PREMIUM", 8, farther, null),
                new PlanAlert(T2, "Boutique Ndiaye", PlanAlert.Kind.EXPIRED, "PREMIUM", null, null, NOW)));

        List<AdminAlert> feed = service.listAlerts(false);

        assertThat(feed).hasSize(2);
        AdminAlert a = feed.stream().filter(x -> x.key().contains(T1.toString())).findFirst().orElseThrow();
        assertThat(a.key()).isEqualTo("plan-expiring:" + T1 + ":2026-10-16");
        assertThat(a.severity()).isEqualTo(AlertSeverity.LOW);
        assertThat(a.description()).isEqualTo("Boutique Awa — abonnement Essai Premium expire dans 3 jours (le 16/10/2026)");
        assertThat(a.occurredAt()).isEqualTo(expiresAt.minus(Duration.ofDays(7)));
        AdminAlert later = feed.stream().filter(x -> x.key().contains(F1.toString())).findFirst().orElseThrow();
        assertThat(later.occurredAt()).isEqualTo(NOW);
        assertNoIds(a);
    }

    @Test
    @DisplayName("WhatsApp failures: > 10 in the last hour → MEDIUM keyed without time, 10 → nothing")
    void whatsappFailures() {
        when(sources.reportFailuresSince(any()))
                .thenReturn(new ReportFailures(10, 2, NOW))
                .thenReturn(new ReportFailures(11, 3, NOW.minusSeconds(60)));

        assertThat(service.listAlerts(false)).isEmpty();
        AdminAlert a = fresh(false).get(0);

        assertThat(a.key()).isEqualTo("whatsapp-failures");
        assertThat(a.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(a.description()).isEqualTo(
                "11 rapports WhatsApp n'ont pas pu être envoyés au cours de la dernière heure (3 entreprises concernées)");
    }

    @Test
    @DisplayName("devices silent for more than 5 days → MEDIUM keyed by the last push")
    void silence() {
        Instant lastPush = NOW.minus(Duration.ofDays(6));
        when(sources.silentTenants(NOW.minus(Duration.ofDays(5)))).thenReturn(List.of(
                new SilentTenant(T1, "Boutique Awa", "kv_abc123", lastPush)));

        AdminAlert a = service.listAlerts(false).get(0);

        assertThat(a.key()).isEqualTo("sync-silence:" + T1 + ":" + lastPush.getEpochSecond());
        assertThat(a.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(a.description()).isEqualTo("Boutique Awa — aucune synchronisation depuis 6 jours (dernière le 07/10/2026)");
        assertNoIds(a);
    }

    @Test
    @DisplayName("health: one HIGH alert per breached metric, distinct keys without time, French description each")
    void healthBreaches() {
        when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW)
                .databaseDown().build());
        assertThat(service.listAlerts(false)).singleElement().satisfies(a -> {
            assertThat(a.key()).isEqualTo("health:database");
            assertThat(a.title()).isEqualTo("Incident système");
            assertThat(a.description()).isEqualTo("Base de données injoignable : le contrôle de connexion a échoué.");
        });

        when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW)
                .errorRate(3.0, true).p99(2500, true).refusals(1200L, true).uptime(98.5, true)
                .connections(9, 10, true).build());
        Map<String, String> byKey = new HashMap<>();
        fresh(false).forEach(a -> {
            assertThat(a.severity()).isEqualTo(AlertSeverity.HIGH);
            byKey.put(a.key(), a.description());
        });

        assertThat(byKey).containsOnlyKeys("health:error-rate", "health:latency", "health:sync-refusals",
                "health:uptime", "health:db-connections");
        assertThat(byKey.get("health:error-rate"))
                .isEqualTo("Taux d'erreurs dépasse le seuil : 3 % sur la dernière heure (seuil 2 %).");
        assertThat(byKey.get("health:latency"))
                .isEqualTo("Temps de réponse (p99) dépasse le seuil : 2 500 ms (seuil 2 000 ms).");
        assertThat(byKey.get("health:sync-refusals"))
                .isEqualTo("Refus de synchronisation dépassent le seuil : 1 200 sur la dernière heure (seuil 1 000).");
        assertThat(byKey.get("health:uptime"))
                .isEqualTo("Disponibilité sur 30 jours sous le seuil : 98,5 % (seuil 99 %).");
        assertThat(byKey.get("health:db-connections"))
                .isEqualTo("Connexions actives à la base : 9 sur 10 (seuil 80 %).");
    }

    @Test
    @DisplayName("handled incident stays hidden across hours while breached; ack deleted on recovery; new breach alerts again")
    void handledConditionLifecycle() {
        Map<String, Instant> store = new HashMap<>();
        when(acks.handledAt(anyCollection())).thenAnswer(inv -> {
            Map<String, Instant> out = new HashMap<>();
            for (Object k : (Collection<?>) inv.getArgument(0)) if (store.containsKey(k)) out.put((String) k, store.get(k));
            return out;
        });
        doAnswer(inv -> { store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(acks).markHandled(any(), any(), any());
        doAnswer(inv -> { ((Collection<?>) inv.getArgument(0)).forEach(store::remove); return 1; })
                .when(acks).delete(anyCollection());
        SystemHealth breached = SystemHealthFixtures.healthy(NOW).errorRate(3.0, true).build();
        when(health.recentHealth()).thenReturn(breached);

        assertThat(service.listAlerts(false)).extracting(AdminAlert::key).containsExactly("health:error-rate");
        service.markHandled("health:error-rate", UUID.randomUUID());
        assertThat(service.listAlerts(false)).isEmpty(); // cache invalidated by the ack

        clock.advance(Duration.ofHours(3)); // still breached hours later
        assertThat(service.listAlerts(false)).isEmpty();
        assertThat(service.listAlerts(true)).extracting(AdminAlert::handled).containsExactly(true);

        when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW).build()); // recovered
        assertThat(fresh(true)).isEmpty();
        assertThat(store).doesNotContainKey("health:error-rate");

        when(health.recentHealth()).thenReturn(breached); // new breach
        assertThat(fresh(false)).extracting(AdminAlert::key).containsExactly("health:error-rate");
    }

    @Test
    @DisplayName("no ack deleted when nothing handled was cleared")
    void noUselessDelete() {
        service.listAlerts(false);
        verify(acks, never()).delete(anyCollection());
    }

    @Test
    @DisplayName("database down: every source throws, acks unreadable — health alerts still returned")
    void outage() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("down");
        when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW).databaseDown().build());
        when(sources.unhandledHighFeedbackSince(any())).thenThrow(down);
        when(sources.deletionPending()).thenThrow(down);
        when(sources.expiredPlansNotFree(any())).thenThrow(down);
        when(sources.reportFailuresSince(any())).thenThrow(down);
        when(sources.silentTenants(any())).thenThrow(down);
        when(planAlerts.listAlerts()).thenThrow(down);
        when(acks.handledAt(anyCollection())).thenThrow(down);

        List<AdminAlert> feed = service.listAlerts(false);

        assertThat(feed).extracting(AdminAlert::key).containsExactly("health:database");
        assertThat(feed.get(0).handled()).isFalse();
        verify(acks, never()).delete(anyCollection()); // whatsapp source unread: its ack is not touched
    }

    @Test
    @DisplayName("handled alerts are hidden by default and listed with includeHandled")
    void ackFiltering() {
        when(sources.unhandledHighFeedbackSince(any())).thenReturn(List.of(
                new HighFeedback(F1, "Boutique Awa", "BUG", "Plante", NOW)));
        when(sources.deletionPending()).thenReturn(List.of(new DeletionPending(T1, "Boutique Awa", NOW.plus(Duration.ofDays(3)))));
        Instant handledAt = NOW.minusSeconds(30);
        when(acks.handledAt(anyCollection())).thenReturn(Map.of("feedback:" + F1, handledAt));

        assertThat(service.listAlerts(false)).extracting(AdminAlert::key).containsExactly("deletion:" + T1 + ":2026-10-16");
        List<AdminAlert> all = service.listAlerts(true);
        assertThat(all).hasSize(2);
        AdminAlert handled = all.stream().filter(AdminAlert::handled).findFirst().orElseThrow();
        assertThat(handled.key()).isEqualTo("feedback:" + F1);
        assertThat(handled.handledAt()).isEqualTo(handledAt);
    }

    @Test
    @DisplayName("count of unhandled alerts by severity, from the cached feed (one computation)")
    void count() {
        when(health.recentHealth()).thenReturn(SystemHealthFixtures.healthy(NOW).errorRate(3.0, true).build());
        when(sources.deletionPending()).thenReturn(List.of(new DeletionPending(T1, "Boutique Awa", NOW.plus(Duration.ofDays(3)))));

        assertThat(service.countUnhandled(AlertSeverity.HIGH)).isEqualTo(1);
        assertThat(service.countUnhandled(AlertSeverity.MEDIUM)).isEqualTo(1);
        assertThat(service.countUnhandled(null)).isEqualTo(2);
        service.listAlerts(false);
        verify(sources, times(1)).deletionPending();
    }

    @Test
    @DisplayName("markHandled stores the ack; a feedback alert also marks the feedback handled (same actor)")
    void markHandled() {
        UUID actor = UUID.randomUUID();
        service.markHandled("feedback:" + F1, actor);
        verify(feedbackInbox).markHandled(F1, actor);
        verify(acks).markHandled("feedback:" + F1, NOW, actor);

        service.markHandled("health:error-rate", actor);
        verify(feedbackInbox, times(1)).markHandled(any(), any());
    }

    @Test
    @DisplayName("feedback already gone: still acked, 204")
    void markHandledFeedbackGone() {
        doThrow(new DomainException(ErrorCode.NOT_FOUND, "gone")).when(feedbackInbox).markHandled(any(), any());
        UUID actor = UUID.randomUUID();
        service.markHandled("feedback:" + F1, actor);
        verify(acks).markHandled("feedback:" + F1, NOW, actor);
    }

    @Test
    @DisplayName("malformed key → 422 VALIDATION_ERROR, nothing stored")
    void malformedKey() {
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> service.markHandled("DROP TABLE x;", actor))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.markHandled("a" + ":b".repeat(150), actor)).isInstanceOf(DomainException.class);
        verifyNoInteractions(acks, feedbackInbox);
    }

    @Test
    @DisplayName("acks older than 90 days are purged")
    void purge() {
        when(acks.purgeBefore(NOW.minus(Duration.ofDays(90)))).thenReturn(4);
        assertThat(service.purgeOldAcks()).isEqualTo(4);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(acks).purgeBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(Instant.parse("2026-07-15T10:00:00Z"));
    }

    @Test
    void labels() {
        assertThat(AdminAlertService.days(1)).isEqualTo("1 jour");
        assertThat(AdminAlertService.planLabel("PREMIUM")).isEqualTo("Premium");
        assertThat(AdminAlertService.feedbackTypeLabel("praise")).isEqualTo("Compliment");
        assertThat(AdminAlertService.feedbackTypeLabel("UNKNOWN_CODE")).isEqualTo("Autre");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
