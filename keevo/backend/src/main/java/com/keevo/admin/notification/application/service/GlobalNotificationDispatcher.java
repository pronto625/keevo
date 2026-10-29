package com.keevo.admin.notification.application.service;

import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason;
import com.keevo.admin.notification.domain.model.GlobalPushSentEvent;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.port.in.DispatchGlobalNotificationsUseCase;
import com.keevo.admin.notification.domain.port.out.GlobalNotificationRepository;
import com.keevo.admin.notification.domain.port.out.PushAudiencePort;
import com.keevo.messaging.api.NotificationPayload;
import com.keevo.messaging.api.PushSendResult;
import com.keevo.messaging.api.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GlobalNotificationDispatcher — sends the due global pushes (Story 9-5, FR82).
 *
 * <p>Each run first ends FAILED / INTERRUPTED the rows SENDING for more than {@link #STUCK_AFTER} (crash or
 * redeploy between claim and completion), then claims the due SCHEDULED rows atomically (SCHEDULED →
 * SENDING in one {@code UPDATE … RETURNING}, on top of the scheduler's ShedLock) so two instances never
 * send a row twice. Audience: every distinct device token of the targeted companies (a token registered
 * in several companies is sent once, cleaned up in the schema it was first seen in), sent per company
 * through {@link NotificationPort#sendToTokens} (FCM multicast in chunks of 500).
 *
 * <p>Outcomes (all with {@code sent_at}): SENT with the FCM counts; FAILED with PUSH_UNAVAILABLE (FCM not
 * configured), NO_RECIPIENT (no device), ALL_CHUNKS_FAILED (every chunk threw) or INTERRUPTED (database
 * outage during the send — counts accumulated so far). A data / SQL error of one company only skips it.
 *
 * <p>Not {@code @Transactional}: one statement per company.
 */
@Service
public class GlobalNotificationDispatcher implements DispatchGlobalNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GlobalNotificationDispatcher.class);

    public static final String PUSH_TYPE = "GLOBAL_ANNOUNCEMENT";
    static final Duration STUCK_AFTER = Duration.ofMinutes(15);

    private final GlobalNotificationRepository repository;
    private final PushAudiencePort audience;
    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GlobalNotificationDispatcher(GlobalNotificationRepository repository, PushAudiencePort audience,
                                        NotificationPort notificationPort, ApplicationEventPublisher events,
                                        Clock clock) {
        this.repository = repository;
        this.audience = audience;
        this.notificationPort = notificationPort;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public int dispatchDue() {
        Instant now = clock.instant();
        for (GlobalNotification stuck : repository.failStuck(now.minus(STUCK_AFTER), now)) {
            log.warn("[ADMIN AUDIT] global push FAILED (INTERRUPTED: SENDING > 15 min) — actor={} id={} target={}",
                    stuck.createdBy(), stuck.id(), stuck.target());
        }
        List<GlobalNotification> due = repository.claimDue(now);
        for (GlobalNotification n : due) {
            send(n);
        }
        return due.size();
    }

    static NotificationPayload payload(GlobalNotification n) {
        // No deep link: the app opens its notifications screen for this type.
        return NotificationPayload.of(PUSH_TYPE, n.title(), n.body(), null,
                Map.of("id", n.id().toString(), "title", n.title(), "body", n.body()));
    }

    private void send(GlobalNotification n) {
        if (!notificationPort.isPushAvailable()) {
            fail(n, GlobalNotificationFailureReason.PUSH_UNAVAILABLE, 0, PushSendResult.EMPTY);
            return;
        }
        NotificationPayload payload = payload(n);
        Set<String> seen = new HashSet<>();
        int recipients = 0;
        PushSendResult total = PushSendResult.EMPTY;
        try {
            for (PushTargetTenant tenant : audience.targetTenants(n.target(), n.targetTenantId())) {
                try {
                    List<String> tokens = new ArrayList<>();
                    for (String token : audience.deviceTokens(tenant)) {
                        if (seen.add(token)) tokens.add(token);
                    }
                    if (tokens.isEmpty()) continue;
                    recipients += tokens.size();
                    total = total.plus(notificationPort.sendToTokens(tenant.schemaName(), tokens, payload));
                } catch (DataAccessResourceFailureException | TransientDataAccessException
                         | RecoverableDataAccessException e) {
                    throw e; // outage: end the row below with what was sent so far
                } catch (RuntimeException e) {
                    log.warn("Global push {}: company {} skipped ({})", n.id(), tenant.id(),
                            e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.error("Global push {} interrupted: {}", n.id(), e.getClass().getSimpleName(), e);
            fail(n, GlobalNotificationFailureReason.INTERRUPTED, recipients, total);
            return;
        }

        if (recipients == 0) {
            fail(n, GlobalNotificationFailureReason.NO_RECIPIENT, 0, total);
        } else if (total.allChunksFailed()) {
            fail(n, GlobalNotificationFailureReason.ALL_CHUNKS_FAILED, recipients, total);
        } else {
            Instant sentAt = clock.instant();
            repository.complete(n.id(), true, null, recipients, total.successCount(), total.failureCount(), sentAt);
            log.info("[ADMIN AUDIT] global push SENT — actor={} id={} target={} recipients={} delivered={} failed={}",
                    n.createdBy(), n.id(), n.target(), recipients, total.successCount(), total.failureCount());
            events.publishEvent(new GlobalPushSentEvent(n.createdBy(), n.target(), recipients, sentAt, n.title()));
        }
    }

    private void fail(GlobalNotification n, GlobalNotificationFailureReason reason, int recipients,
                      PushSendResult total) {
        log.warn("[ADMIN AUDIT] global push FAILED ({}) — actor={} id={} target={} recipients={} delivered={} failed={}",
                reason, n.createdBy(), n.id(), n.target(), recipients, total.successCount(), total.failureCount());
        try {
            repository.complete(n.id(), false, reason, recipients, total.successCount(), total.failureCount(),
                    clock.instant());
        } catch (RuntimeException e) {
            // Left SENDING: the next run (after 15 min) ends it FAILED / INTERRUPTED.
            log.error("Global push {} left SENDING (status not writable): {}", n.id(), e.getClass().getSimpleName());
        }
    }
}
