package com.keevo.admin.alerts.application.service;

import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.in.SendSyncSilenceNoticesUseCase;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.admin.alerts.domain.port.out.SyncSilenceNoticeRepository;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.messaging.api.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * SyncSilenceAlertService — FR81 « tenant J+5 sans sync » proactive owner alert (Story 9-5).
 *
 * <p>Every ACTIVE company with at least one device whose latest push is 5 days old or more gets one
 * WhatsApp to its owner per silence streak: {@code sync_silence_notices} keeps the latest push the
 * notice was sent for, and a newer push (the company synced again) starts a new streak. A company
 * that never synced (no device row) is never notified.
 *
 * <p>When WhatsApp is not configured (NoOp adapter) nothing is recorded. Otherwise the notice is
 * claimed first, then the message sent; when the send fails the claim is deleted so the next daily run
 * retries (same pattern as the 9-2 expiry reminders) — a failing record can never cause daily
 * re-sends. Without an owner phone nothing is recorded (retried next run). Any other error of one
 * company is logged and the run continues; connection / resource failures stop the run.
 */
@Service
public class SyncSilenceAlertService implements SendSyncSilenceNoticesUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncSilenceAlertService.class);

    private final AlertSourcesPort sources;
    private final SyncSilenceNoticeRepository notices;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final WhatsAppPort whatsAppPort;
    private final Clock clock;

    public SyncSilenceAlertService(AlertSourcesPort sources, SyncSilenceNoticeRepository notices,
                                   OwnerRecipientResolver ownerRecipientResolver, WhatsAppPort whatsAppPort,
                                   Clock clock) {
        this.sources = sources;
        this.notices = notices;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.whatsAppPort = whatsAppPort;
        this.clock = clock;
    }

    static String message(long days) {
        return "Bonjour, votre boutique Keevo n'a pas été synchronisée depuis " + days
                + " jours. Ouvrez l'application avec une connexion internet pour sauvegarder vos ventes.";
    }

    @Override
    public int execute() {
        if (!whatsAppPort.isConfigured()) {
            log.info("SyncSilenceAlertService: WhatsApp not configured — no notice sent or recorded");
            return 0;
        }
        Instant now = clock.instant();
        int sent = 0;
        for (SilentTenant tenant : sources.silentTenants(now.minus(AdminAlertService.SYNC_SILENCE).plusSeconds(1))) {
            try {
                if (notify(tenant, now)) sent++;
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e; // outage / pool exhaustion: stop the run, tomorrow's run retries
            } catch (RuntimeException e) {
                log.warn("Sync silence notice: company {} skipped ({})", tenant.tenantId(), e.getClass().getSimpleName());
            }
        }
        log.info("SyncSilenceAlertService: {} notice(s) sent", sent);
        return sent;
    }

    private boolean notify(SilentTenant tenant, Instant now) {
        Optional<Instant> notifiedFor = notices.lastPushAtNotice(tenant.tenantId());
        if (notifiedFor.isPresent() && !notifiedFor.get().isBefore(tenant.lastPushAt())) {
            return false; // already notified for this silence streak
        }
        Optional<String> phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenant.schemaName());
        if (phone.isEmpty()) {
            log.warn("Sync silence notice skipped for company {}: no owner WhatsApp phone — retried next run",
                    tenant.tenantId());
            return false;
        }
        LocalDate today = now.atZone(AdminAlertService.WAT).toLocalDate();
        long days = ChronoUnit.DAYS.between(tenant.lastPushAt().atZone(AdminAlertService.WAT).toLocalDate(), today);
        notices.recordNotice(tenant.tenantId(), now, tenant.lastPushAt()); // claim before sending
        try {
            whatsAppPort.sendReport(phone.get(), message(days));
        } catch (RuntimeException e) {
            log.warn("Sync silence notice failed for company {} — retried next run: {}",
                    tenant.tenantId(), e.getClass().getSimpleName());
            notices.deleteNotice(tenant.tenantId());
            return false;
        }
        log.info("Sync silence notice sent for company {} ({} days)", tenant.tenantId(), days);
        return true;
    }
}
