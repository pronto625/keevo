package com.keevo.clientportal.auth.application;

import com.keevo.clientportal.auth.domain.model.PortalTokens;
import com.keevo.clientportal.auth.domain.port.out.PortalSessionRepository;
import com.keevo.identity.api.ClientPortalCodePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Story 21.7 — sign-in of the end clients of the client portal (Façade):
 * <ul>
 *   <li>code request / check by {@link ClientPortalCodePort} (19.2 machinery, portal purpose: a portal code never opens
 *       a merchant session and vice versa; one SMS budget per phone);</li>
 *   <li>a checked code opens a session: the client row is upserted by phone, a 256-bit token is generated and only its
 *       SHA-256 stored, valid {@link #SESSION_TTL}, the client's sessions beyond the {@value #MAX_SESSIONS} newest
 *       deleted — one short transaction after the code's own;</li>
 *   <li>each portal request is authenticated by the token (shape check, then one primary-key read), limited per
 *       session and per failing IP ({@link PortalRateLimiter}); sign-out deletes the session (same IP brake).</li>
 * </ul>
 * Nothing is cached: a deleted session is refused at its next request. Logs carry no phone and no token.
 */
@Service
public class PortalAuthService {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthService.class);

    static final Duration SESSION_TTL = Duration.ofDays(30);
    static final int MAX_SESSIONS = 10;
    private static final String BEARER = "Bearer ";

    /** An open session: the clear token (handed once to the app) and when it stops working. */
    public record Session(String accessToken, Instant expiresAt) {
    }

    /** The signed-in client of a request: its phone and the key of its session (for the rate limit). */
    public record Client(String phone, String sessionKey) {
    }

    private final ClientPortalCodePort codes;
    private final PortalSessionRepository sessions;
    private final PortalRateLimiter limiter;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PortalAuthService(ClientPortalCodePort codes, PortalSessionRepository sessions, PortalRateLimiter limiter,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.codes = codes;
        this.sessions = sessions;
        this.limiter = limiter;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Sends a portal code to the phone. @return validity of the code in seconds */
    public int requestCode(String phone, String clientIp, String language) {
        return codes.requestCode(phone, clientIp, language);
    }

    /** Checks the code (its attempt committed even when wrong), then opens a session. */
    public Session signIn(String phone, String code, String clientIp) {
        String verified = codes.verifyCode(phone, code, clientIp);
        String token = PortalTokens.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(SESSION_TTL);
        tx.executeWithoutResult(status -> {
            UUID client = sessions.upsertClient(verified, now);
            sessions.insertSession(PortalTokens.hash(token), client, now, expiresAt);
            sessions.trimSessions(client, MAX_SESSIONS);
        });
        log.info("PORTAL_SIGN_IN");
        return new Session(token, expiresAt);
    }

    /**
     * The client of {@code authorization} ({@code Bearer kvc_…}).
     *
     * @throws DomainException {@code PORTAL_SESSION_INVALID} (401) when absent, malformed, unknown or expired;
     *                         {@code PORTAL_TOO_MANY_REQUESTS} (429) instead once this IP failed too often (a
     *                         malformed token is refused without any database access; a valid session is never
     *                         refused because of its IP), or when the session exceeded its own limit
     */
    public Client authenticate(String authorization, String clientIp) {
        String token = token(authorization);
        if (token == null) {
            throw failed(clientIp); // no database access for a malformed token
        }
        byte[] hash = PortalTokens.hash(token);
        // A valid session is never blocked by the failures of its IP (many clients share one carrier NAT address):
        // the IP brake only turns the failed attempts into 429.
        String phone = sessions.phoneOfSession(hash, clock.instant()).orElse(null);
        if (phone == null) {
            throw failed(clientIp);
        }
        String key = HexFormat.of().formatHex(hash);
        if (!limiter.tryAcquire(key)) {
            throw new DomainException(ErrorCode.PORTAL_TOO_MANY_REQUESTS);
        }
        return new Client(phone, key);
    }

    /**
     * Deletes the session of {@code authorization}; a missing or unknown token is a no-op (idempotent). An unknown
     * token counts as a failure of the IP, and an IP over its failure brake deletes nothing (no database access).
     */
    public void signOut(String authorization, String clientIp) {
        String token = token(authorization);
        if (token == null || limiter.tooManyFailures(clientIp)) {
            return;
        }
        if (sessions.deleteSession(PortalTokens.hash(token)) == 0) {
            limiter.recordFailure(clientIp);
        }
    }

    /** Counts a failed authentication of the IP: 429 once over its brake, else 401. */
    private DomainException failed(String clientIp) {
        boolean braked = limiter.tooManyFailures(clientIp);
        limiter.recordFailure(clientIp);
        return braked ? new DomainException(ErrorCode.PORTAL_TOO_MANY_REQUESTS) : invalid();
    }

    /** The token of a {@code Bearer} header when it has the portal token's shape, else null. */
    static String token(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = authorization.substring(BEARER.length()).strip();
        return PortalTokens.looksLikeToken(token) ? token : null;
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.PORTAL_SESSION_INVALID, "Portal session missing, invalid or expired");
    }
}
