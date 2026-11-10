package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue;
import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue.PendingRevocation;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.3b — drains the Sign in with Apple revocation outbox ({@code apple_token_revocations}), filled in the same
 * transaction that removes an Apple link (unlink, account deletion).
 *
 * <p>Every 10 minutes (ShedLock, one instance): at most {@value #BATCH} due rows; each token is decrypted and revoked
 * through {@link AppleTokenClient} (network, no transaction held). Outcomes are written set-based: one DELETE for the
 * revoked rows and for the rows that can never be decrypted (rotated key), one backoff UPDATE (+ one give-up DELETE
 * past {@value #MAX_ATTEMPTS} attempts) for the failures. No-op while the adapter is disabled (rows wait for it).
 * Logs carry counts and row ids only.
 *
 * <p>Cost per run: 1 indexed SELECT (≤ {@value #BATCH} rows), ≤ {@value #BATCH} HTTP calls, ≤ 3 writes.
 */
@Component
public class AppleTokenRevocationJob {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenRevocationJob.class);

    static final int BATCH = 100;
    static final int MAX_ATTEMPTS = 20;

    private final AppleRevocationQueue queue;
    private final AppleTokenClient appleTokenClient;
    private final SecretCipher cipher;

    public AppleTokenRevocationJob(AppleRevocationQueue queue, AppleTokenClient appleTokenClient, SecretCipher cipher) {
        this.queue = queue;
        this.appleTokenClient = appleTokenClient;
        this.cipher = cipher;
    }

    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "appleTokenRevocationJob.run", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!appleTokenClient.enabled() || !cipher.isAvailable()) {
            return;
        }
        List<PendingRevocation> due = queue.findDue(BATCH);
        if (due.isEmpty()) {
            return;
        }
        List<UUID> done = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        int undecryptable = 0;
        for (PendingRevocation row : due) {
            String token;
            try {
                token = cipher.decrypt(row.token(), row.authenticatorId().toString());
            } catch (RuntimeException e) {
                // Rotated key or corrupted blob: it will never open — dropped, logged by id.
                log.error("[social] APPLE revocation {} dropped: token cannot be decrypted", row.id());
                undecryptable++;
                done.add(row.id());
                continue;
            }
            try {
                appleTokenClient.revoke(row.clientId(), token);
                done.add(row.id());
            } catch (RuntimeException e) {
                log.warn("[social] APPLE revocation {} failed ({}), attempt {}", row.id(),
                        e.getClass().getSimpleName(), row.attempts() + 1);
                failed.add(row.id());
            }
        }
        queue.delete(done);
        int givenUp = queue.recordFailures(failed, MAX_ATTEMPTS);
        if (givenUp > 0) {
            log.error("[social] {} APPLE revocation(s) given up after {} attempts", givenUp, MAX_ATTEMPTS);
        }
        log.info("[social] APPLE revocations: {} done, {} undecryptable, {} failed", done.size() - undecryptable,
                undecryptable, failed.size());
    }
}
