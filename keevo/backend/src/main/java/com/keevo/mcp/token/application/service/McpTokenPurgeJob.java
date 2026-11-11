package com.keevo.mcp.token.application.service;

import com.keevo.mcp.token.domain.port.out.McpAccessTokenRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Story 21.9 — nightly removal of the personal access tokens expired for more than {@value #KEEP_EXPIRED_DAYS} days
 * (an expired token is refused at once; it stays listed as « Expiré » for a while so the owner understands why the
 * client stopped). One set-based DELETE on {@code idx_mcp_access_tokens_expires_at}; one runner across instances
 * (ShedLock).
 *
 * <p>21.9 reprise 2026-11-11 (co-owners) — then the dead tokens: holder no longer an active owner of the company
 * (demoted or deactivated co-owner) or cut by a revocation cutoff; refused at once already, they must not keep the
 * person's id and the token's name until their expiry. One set-based DELETE (anti-join on the memberships' unique
 * index). Each purge runs on its own: one failing never stops the other.
 */
@Component
public class McpTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(McpTokenPurgeJob.class);

    static final int KEEP_EXPIRED_DAYS = 30;

    private final McpAccessTokenRepository tokens;
    private final Clock clock;

    public McpTokenPurgeJob(McpAccessTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Scheduled(cron = "${keevo.mcp-token-purge.cron:0 55 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "mcpTokenPurgeJob.purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            int deleted = tokens.purgeExpiredBefore(clock.instant().minus(Duration.ofDays(KEEP_EXPIRED_DAYS)));
            if (deleted > 0) {
                log.info("MCP token purge: {} token(s) expired for more than {} days deleted", deleted,
                        KEEP_EXPIRED_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("MCP token purge failed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
        try {
            int dead = tokens.purgeDead();
            if (dead > 0) {
                log.info("MCP token purge: {} token(s) of a former owner or cut by a revocation deleted", dead);
            }
        } catch (RuntimeException e) {
            log.warn("MCP dead token purge failed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
