package com.keevo.admin.alerts.application.service;

import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.admin.alerts.domain.port.out.SyncSilenceNoticeRepository;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.messaging.api.WhatsAppPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncSilenceAlertService — FR81 (Story 9-5)")
class SyncSilenceAlertServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z"); // 10:00 WAT
    static final UUID T1 = UUID.randomUUID();
    static final UUID T2 = UUID.randomUUID();
    static final String PHONE = "+237600000001";

    @Mock AlertSourcesPort sources;
    @Mock SyncSilenceNoticeRepository notices;
    @Mock OwnerRecipientResolver resolver;
    @Mock WhatsAppPort whatsApp;

    SyncSilenceAlertService service;

    @BeforeEach
    void setUp() {
        service = new SyncSilenceAlertService(sources, notices, resolver, whatsApp, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(resolver.resolveOwnerWhatsAppPhone("kv_aaa111")).thenReturn(Optional.of(PHONE));
        lenient().when(whatsApp.isConfigured()).thenReturn(true);
    }

    private static SilentTenant silent(UUID id, String schema, Instant lastPush) {
        return new SilentTenant(id, "Boutique", schema, lastPush);
    }

    @Test
    @DisplayName("silent 6 days, never notified → one WhatsApp with the French text, notice recorded")
    void firstNotice() {
        Instant lastPush = NOW.minus(Duration.ofDays(6));
        when(sources.silentTenants(any())).thenReturn(List.of(silent(T1, "kv_aaa111", lastPush)));
        when(notices.lastPushAtNotice(T1)).thenReturn(Optional.empty());

        assertThat(service.execute()).isEqualTo(1);

        verify(whatsApp).sendReport(PHONE, "Bonjour, votre boutique Keevo n'a pas été synchronisée depuis 6 jours. "
                + "Ouvrez l'application avec une connexion internet pour sauvegarder vos ventes.");
        verify(notices).recordNotice(T1, NOW, lastPush);
    }

    @Test
    @DisplayName("the 5-day cutoff includes exactly 5 days of silence")
    void cutoff() {
        when(sources.silentTenants(any())).thenReturn(List.of());
        service.execute();
        verify(sources).silentTenants(NOW.minus(Duration.ofDays(5)).plusSeconds(1));
    }

    @Test
    @DisplayName("next day, same streak → nothing")
    void sameStreak() {
        Instant lastPush = NOW.minus(Duration.ofDays(7));
        when(sources.silentTenants(any())).thenReturn(List.of(silent(T1, "kv_aaa111", lastPush)));
        when(notices.lastPushAtNotice(T1)).thenReturn(Optional.of(lastPush));

        assertThat(service.execute()).isZero();
        verify(whatsApp, never()).sendReport(anyString(), anyString());
        verify(notices, never()).recordNotice(any(), any(), any());
    }

    @Test
    @DisplayName("a newer push then 5 more silent days → new streak, new WhatsApp")
    void newStreak() {
        Instant oldPush = NOW.minus(Duration.ofDays(20));
        Instant newPush = NOW.minus(Duration.ofDays(5));
        when(sources.silentTenants(any())).thenReturn(List.of(silent(T1, "kv_aaa111", newPush)));
        when(notices.lastPushAtNotice(T1)).thenReturn(Optional.of(oldPush));

        assertThat(service.execute()).isEqualTo(1);
        verify(notices).recordNotice(T1, NOW, newPush);
    }

    @Test
    @DisplayName("no owner phone → skipped, logged, not recorded (retried next run); next company still processed")
    void noPhone() {
        when(sources.silentTenants(any())).thenReturn(List.of(
                silent(T2, "kv_bbb222", NOW.minus(Duration.ofDays(6))),
                silent(T1, "kv_aaa111", NOW.minus(Duration.ofDays(6)))));
        when(notices.lastPushAtNotice(any())).thenReturn(Optional.empty());
        when(resolver.resolveOwnerWhatsAppPhone("kv_bbb222")).thenReturn(Optional.empty());

        assertThat(service.execute()).isEqualTo(1);
        verify(notices, never()).recordNotice(org.mockito.ArgumentMatchers.eq(T2), any(), any());
        verify(whatsApp).sendReport(org.mockito.ArgumentMatchers.eq(PHONE), anyString());
    }

    @Test
    @DisplayName("notice claimed before the send; WhatsApp failure → claim deleted (retried next run)")
    void sendFails() {
        Instant lastPush = NOW.minus(Duration.ofDays(6));
        when(sources.silentTenants(any())).thenReturn(List.of(silent(T1, "kv_aaa111", lastPush)));
        when(notices.lastPushAtNotice(T1)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("wassender down")).when(whatsApp).sendReport(anyString(), anyString());

        assertThat(service.execute()).isZero();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(notices, whatsApp);
        order.verify(notices).recordNotice(T1, NOW, lastPush);
        order.verify(whatsApp).sendReport(anyString(), anyString());
        order.verify(notices).deleteNotice(T1);
    }

    @Test
    @DisplayName("recording the notice fails → nothing sent (no daily re-send); next company still processed")
    void recordFails() {
        when(sources.silentTenants(any())).thenReturn(List.of(
                silent(T2, "kv_bbb222", NOW.minus(Duration.ofDays(6))),
                silent(T1, "kv_aaa111", NOW.minus(Duration.ofDays(6)))));
        when(notices.lastPushAtNotice(any())).thenReturn(Optional.empty());
        when(resolver.resolveOwnerWhatsAppPhone("kv_bbb222")).thenReturn(Optional.of("+237600000002"));
        doThrow(new BadSqlGrammarException("x", "sql", new SQLException("bad")))
                .when(notices).recordNotice(org.mockito.ArgumentMatchers.eq(T2), any(), any());

        assertThat(service.execute()).isEqualTo(1);
        verify(whatsApp, never()).sendReport(org.mockito.ArgumentMatchers.eq("+237600000002"), anyString());
        verify(whatsApp).sendReport(org.mockito.ArgumentMatchers.eq(PHONE), anyString());
    }

    @Test
    @DisplayName("WhatsApp not configured (NoOp adapter) → nothing sent, nothing recorded")
    void notConfigured() {
        when(whatsApp.isConfigured()).thenReturn(false);
        assertThat(service.execute()).isZero();
        verifyNoInteractions(sources, notices, resolver);
        verify(whatsApp, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("an unexpected error of one company (not database) → logged, run continues")
    void unexpectedErrorContinues() {
        when(sources.silentTenants(any())).thenReturn(List.of(
                silent(T2, "kv_bbb222", NOW.minus(Duration.ofDays(6))),
                silent(T1, "kv_aaa111", NOW.minus(Duration.ofDays(6)))));
        when(notices.lastPushAtNotice(any())).thenReturn(Optional.empty());
        when(resolver.resolveOwnerWhatsAppPhone("kv_bbb222")).thenThrow(new IllegalStateException("bad phone"));

        assertThat(service.execute()).isEqualTo(1);
    }

    @Test
    @DisplayName("a company's own SQL error skips it; a connection failure stops the run")
    void dbErrors() {
        when(sources.silentTenants(any())).thenReturn(List.of(
                silent(T2, "kv_bbb222", NOW.minus(Duration.ofDays(6))),
                silent(T1, "kv_aaa111", NOW.minus(Duration.ofDays(6)))));
        when(notices.lastPushAtNotice(T2)).thenThrow(new BadSqlGrammarException("x", "sql", new SQLException("bad")));
        when(notices.lastPushAtNotice(T1)).thenReturn(Optional.empty());
        assertThat(service.execute()).isEqualTo(1);

        doThrow(new DataAccessResourceFailureException("down")).when(notices).lastPushAtNotice(T2);
        assertThatThrownBy(() -> service.execute()).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
