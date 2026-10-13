package com.keevo.admin.notification.application.service;

import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationCommand;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationUseCase.Scheduled;
import com.keevo.admin.notification.domain.port.out.GlobalNotificationRepository;
import com.keevo.admin.notification.domain.port.out.PushAudiencePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalNotificationService (Story 9-5 — global push)")
class GlobalNotificationServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    @Mock GlobalNotificationRepository repository;
    @Mock PushAudiencePort audience;

    GlobalNotificationService service;
    final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GlobalNotificationService(repository, audience, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ScheduleGlobalNotificationCommand cmd(String title, String body, String target, String tenant, String at) {
        return new ScheduleGlobalNotificationCommand(title, body, target, tenant, at, actor);
    }

    private DomainException validationError(ScheduleGlobalNotificationCommand c) {
        return (DomainException) org.assertj.core.api.Assertions.catchThrowable(() -> service.schedule(c));
    }

    @Test
    void scheduleNow_storesScheduledRowAtNow_andReturnsEstimate() {
        List<PushTargetTenant> tenants = List.of(new PushTargetTenant(UUID.randomUUID(), "kv_aaaaaa"));
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null)).thenReturn(tenants);
        when(audience.countDeviceTokens(tenants)).thenReturn(5L);

        Scheduled result = service.schedule(cmd("  Maintenance  ", "Ce soir 22h", "all", null, null));

        ArgumentCaptor<GlobalNotification> row = ArgumentCaptor.forClass(GlobalNotification.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().title()).isEqualTo("Maintenance");
        assertThat(row.getValue().target()).isEqualTo(GlobalNotificationTarget.ALL);
        assertThat(row.getValue().targetTenantId()).isNull();
        assertThat(row.getValue().scheduledAt()).isEqualTo(NOW);
        assertThat(row.getValue().status()).isEqualTo(GlobalNotificationStatus.SCHEDULED);
        assertThat(row.getValue().createdBy()).isEqualTo(actor);
        assertThat(result.id()).isEqualTo(row.getValue().id());
        assertThat(result.recipientEstimate()).isEqualTo(5);
    }

    @Test
    void scheduleLater_keepsTheDate() {
        service.schedule(cmd("T", "B", "PREMIUM", null, "2026-10-13T12:00:00+01:00"));
        ArgumentCaptor<GlobalNotification> row = ArgumentCaptor.forClass(GlobalNotification.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().scheduledAt()).isEqualTo(Instant.parse("2026-10-13T11:00:00Z"));
        assertThat(row.getValue().target()).isEqualTo(GlobalNotificationTarget.PREMIUM);
    }

    @Test
    void titleAndBodyLimits() {
        DomainException e = validationError(cmd("x".repeat(81), "y".repeat(201), "ALL", null, null));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(e.getDetails()).containsKeys("title", "body");
        verify(repository, never()).insert(any());

        service.schedule(cmd("x".repeat(80), "y".repeat(200), "ALL", null, null));
        verify(repository).insert(any());
    }

    @Test
    void blankTitleOrBody_rejected() {
        assertThat(validationError(cmd("  ", null, "ALL", null, null)).getDetails()).containsKeys("title", "body");
    }

    @Test
    void unknownOrMissingTarget_rejected() {
        assertThat(validationError(cmd("T", "B", "VIP", null, null)).getDetails()).containsKey("target");
        assertThat(validationError(cmd("T", "B", null, null, null)).getDetails()).containsKey("target");
    }

    @Test
    void tenantTargetWithoutTenant_rejected() {
        assertThat(validationError(cmd("T", "B", "TENANT", null, null)).getDetails()).containsKey("targetTenantId");
        assertThat(validationError(cmd("T", "B", "TENANT", "not-a-uuid", null)).getDetails())
                .containsKey("targetTenantId");
    }

    @Test
    void tenantTargetOnUnreachableTenant_rejected() {
        UUID id = UUID.randomUUID();
        when(audience.reachableTenant(id)).thenReturn(Optional.empty());
        assertThat(validationError(cmd("T", "B", "TENANT", id.toString(), null)).getDetails())
                .containsKey("targetTenantId");
    }

    @Test
    void tenantTarget_storesTheTenant() {
        UUID id = UUID.randomUUID();
        when(audience.reachableTenant(id)).thenReturn(Optional.of(new PushTargetTenant(id, "kv_aaaaaa")));
        service.schedule(cmd("T", "B", "TENANT", id.toString(), null));
        ArgumentCaptor<GlobalNotification> row = ArgumentCaptor.forClass(GlobalNotification.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().targetTenantId()).isEqualTo(id);
    }

    @Test
    void tenantIdOnOtherTarget_rejected() {
        assertThat(validationError(cmd("T", "B", "ALL", UUID.randomUUID().toString(), null)).getDetails())
                .containsKey("targetTenantId");
    }

    @Test
    void scheduledAtWindow() {
        assertThat(validationError(cmd("T", "B", "ALL", null, "2026-10-13T09:58:00Z")).getDetails())
                .containsKey("scheduledAt");
        assertThat(validationError(cmd("T", "B", "ALL", null, "2026-11-12T10:00:01Z")).getDetails())
                .containsKey("scheduledAt");
        assertThat(validationError(cmd("T", "B", "ALL", null, "demain")).getDetails()).containsKey("scheduledAt");
        // tolerance of one minute in the past, up to 30 days ahead
        service.schedule(cmd("T", "B", "ALL", null, "2026-10-13T09:59:30Z"));
        service.schedule(cmd("T", "B", "ALL", null, "2026-11-12T10:00:00Z"));
    }

    @Test
    void cancel_scheduled() {
        UUID id = UUID.randomUUID();
        when(repository.cancelIfScheduled(id, NOW)).thenReturn(true);
        service.cancel(id, actor);
        verify(repository, never()).findById(any());
    }

    @Test
    void cancel_alreadySent_isInvalidState() {
        UUID id = UUID.randomUUID();
        when(repository.cancelIfScheduled(id, NOW)).thenReturn(false);
        when(repository.findById(id)).thenReturn(Optional.of(new GlobalNotification(id, "T", "B",
                GlobalNotificationTarget.ALL, null, null, NOW, GlobalNotificationStatus.SENT, 1, 1, 0, null, actor, NOW, NOW)));
        assertThatThrownBy(() -> service.cancel(id, actor))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void cancel_unknown_isNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(id, actor))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void estimate_validatesAndCounts() {
        List<PushTargetTenant> tenants = List.of(new PushTargetTenant(UUID.randomUUID(), "kv_aaaaaa"));
        when(audience.targetTenants(GlobalNotificationTarget.TRIAL, null)).thenReturn(tenants);
        when(audience.countDeviceTokens(tenants)).thenReturn(3L);
        assertThat(service.estimate("TRIAL", null)).isEqualTo(3);
        assertThatThrownBy(() -> service.estimate("TENANT", ""))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void history_andPurge_use90Days() {
        service.history();
        verify(repository).findCreatedSince(Instant.parse("2026-07-15T10:00:00Z"));
        service.purgeOld();
        verify(repository).purgeCreatedBefore(Instant.parse("2026-07-15T10:00:00Z"));
    }

    @Test
    void estimateComputedBeforeInsert() {
        service.schedule(cmd("T", "B", "ALL", null, null));
        InOrder order = inOrder(audience, repository);
        order.verify(audience).countDeviceTokens(any());
        order.verify(repository).insert(any());
    }

    @Test
    void estimateFailure_isServiceUnavailable_andNothingStored() {
        when(audience.targetTenants(GlobalNotificationTarget.ALL, null))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> service.schedule(cmd("T", "B", "ALL", null, null)))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        verify(repository, never()).insert(any());
    }

    @Test
    void unicodeSpacesAreStripped() {
        // U+2003 EM SPACE: trimmed by JS trim() and Java strip(), not by Java trim()
        assertThat(validationError(cmd("\u2003", "\u2003", "ALL", null, null)).getDetails())
                .containsKeys("title", "body");
        service.schedule(cmd("\u2003Maintenance\u2003", "B", "ALL", null, null));
        ArgumentCaptor<GlobalNotification> row = ArgumentCaptor.forClass(GlobalNotification.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().title()).isEqualTo("Maintenance");
    }

    @Test
    void searchTenants_stripsAndLimits() {
        service.searchTenants("  Awa ");
        verify(audience).searchReachable("Awa", 20);
    }

    @Test
    void announcements_ofTheCallerCompany_atMost90Days() {
        PushTenantRef tenant = new PushTenantRef(UUID.randomUUID(), "Boutique Awa", "FREE");
        when(audience.tenantOfSchema("kv_aaaaaa")).thenReturn(Optional.of(tenant));
        Announcement a = new Announcement(UUID.randomUUID(), "T", "B", NOW);
        when(repository.findSentFor(any(), any())).thenReturn(List.of(a));

        assertThat(service.announcements("kv_aaaaaa", null)).containsExactly(a);
        verify(repository).findSentFor(tenant, Instant.parse("2026-07-15T10:00:00Z"));
        service.announcements("kv_aaaaaa", "2020-01-01T00:00:00Z");
        verify(repository, org.mockito.Mockito.times(2)).findSentFor(tenant, Instant.parse("2026-07-15T10:00:00Z"));
        service.announcements("kv_aaaaaa", "2026-10-12T10:00:00Z");
        verify(repository).findSentFor(tenant, Instant.parse("2026-10-12T10:00:00Z"));
        assertThatThrownBy(() -> service.announcements("kv_aaaaaa", "hier"))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void announcements_unknownCompany_empty() {
        when(audience.tenantOfSchema("kv_zzzzzz")).thenReturn(Optional.empty());
        assertThat(service.announcements("kv_zzzzzz", null)).isEmpty();
    }
}
