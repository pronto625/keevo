package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.ExpoPushTicket;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoPushTicketRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoReceiptClient;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Story 19.9b — Expo push receipts (Expo's documented batch pattern): every 15 min, the tickets stored by
 * {@code ExpoPushAdapter} that are older than 15 min are read by batches of {@value ExpoReceiptClient#MAX_IDS}
 * (keyset on {@code created_at, ticket_id}) and their receipts asked to Expo. Tokens whose receipt says
 * {@code DeviceNotRegistered} are deleted with one statement per tenant schema of the batch; other errors are logged
 * at WARN by count; the tickets that got a receipt are deleted in one statement. Tickets older than 24 h are deleted
 * unread (Expo keeps receipts 24 h). A failed receipt call stops the run: the tickets stay for the next one.
 *
 * <p>Per run: 1 purge, then per batch 1 SELECT + 1 receipt call + at most one token DELETE per tenant with dead
 * tokens + 1 ticket DELETE. Active only when Expo push is on.
 */
@Component
@ConditionalOnProperty(name = "keevo.push.expo.enabled", havingValue = "true")
public class ExpoReceiptJob {

    private static final Logger log = LoggerFactory.getLogger(ExpoReceiptJob.class);

    static final Duration RECEIPT_DELAY = Duration.ofMinutes(15);
    static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
    static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";

    private final ExpoPushTicketRepository tickets;
    private final ExpoReceiptClient receipts;
    private final DeviceTokenRepository deviceTokens;
    private final Clock clock;

    public ExpoReceiptJob(ExpoPushTicketRepository tickets, ExpoReceiptClient receipts,
                          DeviceTokenRepository deviceTokens, Clock clock) {
        this.tickets = tickets;
        this.receipts = receipts;
        this.deviceTokens = deviceTokens;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @SchedulerLock(name = "expoReceiptJob.run", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        try {
            int purged = tickets.purgeCreatedBefore(now.minus(RECEIPT_RETENTION));
            if (purged > 0) log.info("[Expo] {} ticket(s) older than 24 h dropped unread", purged);
        } catch (RuntimeException e) {
            log.warn("[Expo] ticket purge failed ({})", e.getClass().getSimpleName());
        }
        Instant before = now.minus(RECEIPT_DELAY);
        Instant afterCreatedAt = null;
        String afterTicketId = null;
        while (true) {
            List<ExpoPushTicket> batch;
            try {
                batch = tickets.findCreatedBefore(before, afterCreatedAt, afterTicketId, ExpoReceiptClient.MAX_IDS);
            } catch (RuntimeException e) {
                log.warn("[Expo] ticket read failed ({})", e.getClass().getSimpleName());
                return;
            }
            if (batch.isEmpty()) return;
            if (!process(batch)) return;
            if (batch.size() < ExpoReceiptClient.MAX_IDS) return;
            ExpoPushTicket last = batch.get(batch.size() - 1);
            afterCreatedAt = last.createdAt();
            afterTicketId = last.ticketId();
        }
    }

    /** False when the receipt call failed (the run stops). */
    private boolean process(List<ExpoPushTicket> batch) {
        Map<String, String> errors;
        try {
            errors = receipts.fetchReceipts(batch.stream().map(ExpoPushTicket::ticketId).toList());
        } catch (RuntimeException e) {
            log.warn("[Expo] receipt call failed for {} ticket(s) ({}) — retried next run", batch.size(),
                    e.getClass().getSimpleName());
            return false;
        }
        Map<String, List<String>> deadBySchema = new LinkedHashMap<>();
        Map<String, Integer> otherErrors = new TreeMap<>();
        List<String> processed = new ArrayList<>();
        for (ExpoPushTicket ticket : batch) {
            String error = errors.get(ticket.ticketId());
            if (error == null) continue; // no receipt yet: kept until it comes or the 24 h purge
            processed.add(ticket.ticketId());
            if (DEVICE_NOT_REGISTERED.equals(error)) {
                deadBySchema.computeIfAbsent(ticket.tenantSchema(), s -> new ArrayList<>()).add(ticket.token());
            } else if (!error.isEmpty()) {
                otherErrors.merge(error, 1, Integer::sum);
            }
        }
        deadBySchema.forEach(this::deleteTokens);
        otherErrors.forEach((error, count) -> log.warn("[Expo] {} receipt(s) with error {}", count, error));
        try {
            tickets.deleteByIds(processed);
        } catch (RuntimeException e) {
            log.warn("[Expo] processed ticket cleanup failed ({})", e.getClass().getSimpleName());
        }
        return true;
    }

    private void deleteTokens(String tenantSchema, List<String> tokens) {
        if (!TenantSchema.isValid(tenantSchema)) return;
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantSchema);
        try {
            int deleted = deviceTokens.deleteByTokens(tokens.stream().distinct().toList());
            log.info("[Expo] removed {} unregistered token(s) for tenant={}", deleted, tenantSchema);
        } catch (RuntimeException e) {
            log.warn("[Expo] unregistered token cleanup failed for tenant={} ({})", tenantSchema,
                    e.getClass().getSimpleName());
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setCurrentTenant(previous);
        }
    }
}
