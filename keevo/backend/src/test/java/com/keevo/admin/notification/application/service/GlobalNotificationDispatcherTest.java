package com.keevo.admin.notification.application.service;

import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.GlobalPushSentEvent;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.port.out.GlobalNotificationRepository;
import com.keevo.admin.notification.domain.port.out.PushAudiencePort;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.capability.notification.PushNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason.ALL_CHUNKS_FAILED;
import static com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason.INTERRUPTED;
import static com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason.NO_RECIPIENT;
import static com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason.PUSH_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalNotificationDispatcher (Story 9-5 — global push)")
class GlobalNotificationDispatcherTest {

    static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    @Mock GlobalNotificationRepository repository;
    @Mock PushAudiencePort audience;
    @Mock PushNotifier pushNotifier;
    @Mock ApplicationEventPublisher events;

    GlobalNotificationDispatcher dispatcher;
    final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new GlobalNotificationDispatcher(repository, audience, pushNotifier, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.failStuck(any(), any())).thenReturn(List.of());
    }

    private GlobalNotification due(GlobalNotificationTarget target, UUID tenantId) {
        return new GlobalNotification(UUID.randomUUID(), "Maintenance", "Ce soir 22h", target, tenantId, null,
                NOW.minusSeconds(30), GlobalNotificationStatus.SENDING, null, null, null, null, actor, NOW, null);
    }

    private static PushTargetTenant tenant(String schema) {
        return new PushTargetTenant(UUID.randomUUID(), schema);
    }

    @Test
    void nothingDue_noSend() {
        when(repository.claimDue(NOW)).thenReturn(List.of());
        assertThat(dispatcher.dispatchDue()).isZero();
        verifyNoInteractions(pushNotifier, audience, events);
    }

    @Test
    void stuckSendingRowsAreFailedBeforeClaiming() {
        when(repository.failStuck(NOW.minusSeconds(15 * 60), NOW)).thenReturn(List.of(due(GlobalNotificationTarget.ALL, null)));
        when(repository.claimDue(NOW)).thenReturn(List.of());

        dispatcher.dispatchDue();

        InOrder order = inOrder(repository);
        order.verify(repository).failStuck(NOW.minusSeconds(15 * 60), NOW);
        order.verify(repository).claimDue(NOW);
    }

    @Test
    void sendNowAll_sendsEveryTenantTokens_andStoresCounts() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        PushTargetTenant a = tenant("kv_aaaaaa");
        PushTargetTenant b = tenant("kv_bbbbbb");
        PushTargetTenant c = tenant("kv_cccccc");
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(List.of(a, b, c));
        when(audience.deviceTokens(a)).thenReturn(List.of("a1", "a2"));
        when(audience.deviceTokens(b)).thenReturn(List.of("b1", "b2", "b3"));
        when(audience.deviceTokens(c)).thenReturn(List.of());
        when(pushNotifier.sendToTokens(eq("kv_aaaaaa"), eq(List.of("a1", "a2")), any()))
                .thenReturn(new PushSendResult(2, 0, 1, 0));
        when(pushNotifier.sendToTokens(eq("kv_bbbbbb"), eq(List.of("b1", "b2", "b3")), any()))
                .thenReturn(new PushSendResult(2, 1, 1, 0));

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        verify(repository).complete(n.id(), true, null, 5, 4, 1, NOW);
        verify(pushNotifier, never()).sendToTokens(eq("kv_cccccc"), anyList(), any());
        ArgumentCaptor<GlobalPushSentEvent> event = ArgumentCaptor.forClass(GlobalPushSentEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new GlobalPushSentEvent(actor, GlobalNotificationTarget.ALL, 5, NOW, "Maintenance"));
    }

    @Test
    void sameTokenInSeveralCompanies_sentOnce_inTheFirstSchema() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        PushTargetTenant a = tenant("kv_aaaaaa");
        PushTargetTenant b = tenant("kv_bbbbbb");
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(List.of(a, b));
        when(audience.deviceTokens(a)).thenReturn(List.of("shared", "a1"));
        when(audience.deviceTokens(b)).thenReturn(List.of("shared", "b1"));
        when(pushNotifier.sendToTokens(eq("kv_aaaaaa"), eq(List.of("shared", "a1")), any()))
                .thenReturn(new PushSendResult(2, 0, 1, 0));
        when(pushNotifier.sendToTokens(eq("kv_bbbbbb"), eq(List.of("b1")), any()))
                .thenReturn(new PushSendResult(1, 0, 1, 0));

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), true, null, 3, 3, 0, NOW);
    }

    @Test
    void payload_isGlobalAnnouncementWithoutDeepLink() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        NotificationPayload p = GlobalNotificationDispatcher.payload(n);
        assertThat(p.type()).isEqualTo("GLOBAL_ANNOUNCEMENT");
        assertThat(p.title()).isEqualTo("Maintenance");
        assertThat(p.body()).isEqualTo("Ce soir 22h");
        assertThat(p.deepLink()).isNull();
        assertThat(p.metadata()).containsEntry("title", "Maintenance").containsEntry("body", "Ce soir 22h")
                .containsEntry("id", n.id().toString());
    }

    @Test
    void premiumTarget_onlyAsksThePremiumAudience() {
        GlobalNotification n = due(GlobalNotificationTarget.PREMIUM, null);
        PushTargetTenant p = tenant("kv_pppppp");
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.PREMIUM, null)).thenReturn(List.of(p));
        when(audience.deviceTokens(p)).thenReturn(List.of("p1"));
        when(pushNotifier.sendToTokens(eq("kv_pppppp"), eq(List.of("p1")), any()))
                .thenReturn(new PushSendResult(1, 0, 1, 0));

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), true, null, 1, 1, 0, NOW);
    }

    @Test
    void fcmNotConfigured_failedPushUnavailable() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(false);

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), false, PUSH_UNAVAILABLE, 0, 0, 0, NOW);
        verifyNoInteractions(audience, events);
        verify(pushNotifier, never()).sendToTokens(anyString(), anyList(), any());
    }

    @Test
    void everyChunkThrew_failedAllChunks() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        PushTargetTenant a = tenant("kv_aaaaaa");
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(List.of(a));
        when(audience.deviceTokens(a)).thenReturn(List.of("a1", "a2"));
        when(pushNotifier.sendToTokens(eq("kv_aaaaaa"), anyList(), any()))
                .thenReturn(new PushSendResult(0, 2, 1, 1));

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), false, ALL_CHUNKS_FAILED, 2, 0, 2, NOW);
        verifyNoInteractions(events);
    }

    @Test
    void emptyAudience_failedNoRecipient() {
        GlobalNotification n = due(GlobalNotificationTarget.FREE, null);
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.FREE, null)).thenReturn(List.of());

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), false, NO_RECIPIENT, 0, 0, 0, NOW);
        verifyNoInteractions(events);
    }

    @Test
    void oneCompanyFailing_skipped_othersStillSent() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        PushTargetTenant a = tenant("kv_aaaaaa");
        PushTargetTenant broken = tenant("kv_bbbbbb");
        PushTargetTenant c = tenant("kv_cccccc");
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(List.of(a, broken, c));
        when(audience.deviceTokens(a)).thenReturn(List.of("a1"));
        when(audience.deviceTokens(broken)).thenThrow(new BadSqlGrammarException("x", "y", new SQLException()));
        when(audience.deviceTokens(c)).thenReturn(List.of("c1"));
        when(pushNotifier.sendToTokens(anyString(), anyList(), any())).thenReturn(new PushSendResult(1, 0, 1, 0));

        dispatcher.dispatchDue();

        verify(repository).complete(n.id(), true, null, 2, 2, 0, NOW);
    }

    @Test
    void outageAfterFirstCompany_failedInterrupted_withCountsSoFar_nextRowStillProcessed() {
        GlobalNotification broken = due(GlobalNotificationTarget.ALL, null);
        GlobalNotification next = due(GlobalNotificationTarget.FREE, null);
        PushTargetTenant a = tenant("kv_aaaaaa");
        PushTargetTenant b = tenant("kv_bbbbbb");
        when(repository.claimDue(NOW)).thenReturn(List.of(broken, next));
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(List.of(a, b));
        when(audience.deviceTokens(a)).thenReturn(List.of("a1", "a2"));
        when(pushNotifier.sendToTokens(eq("kv_aaaaaa"), anyList(), any())).thenReturn(new PushSendResult(2, 0, 1, 0));
        when(audience.deviceTokens(b)).thenThrow(new DataAccessResourceFailureException("down"));
        when(audience.targetTenants(GlobalNotificationTarget.FREE, null)).thenReturn(List.of());

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);

        verify(repository).complete(broken.id(), false, INTERRUPTED, 2, 2, 0, NOW);
        verify(repository).complete(next.id(), false, NO_RECIPIENT, 0, 0, 0, NOW);
    }

    @Test
    void completeFailing_doesNotStopTheRun() {
        GlobalNotification n = due(GlobalNotificationTarget.ALL, null);
        when(repository.claimDue(NOW)).thenReturn(List.of(n));
        when(pushNotifier.isPushAvailable()).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("down")).when(repository)
                .complete(any(), anyBoolean(), any(), anyInt(), anyInt(), anyInt(), any());

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
    }

    @Test
    void claimIsTheOnlyWayToSend_secondRunFindsNothing() {
        GlobalNotification n = due(GlobalNotificationTarget.FREE, null);
        when(repository.claimDue(NOW)).thenReturn(List.of(n), List.of());
        when(pushNotifier.isPushAvailable()).thenReturn(true);
        when(audience.targetTenants(GlobalNotificationTarget.FREE, null)).thenReturn(List.of());

        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        verify(repository).complete(eq(n.id()), anyBoolean(), any(), anyInt(), anyInt(), anyInt(), any());
    }
}
