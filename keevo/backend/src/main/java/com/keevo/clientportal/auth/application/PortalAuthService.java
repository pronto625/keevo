package com.keevo.clientportal.auth.application;

import com.keevo.clientportal.auth.domain.model.PortalTokens;
import com.keevo.clientportal.auth.domain.port.out.PortalSessionRepository;
import com.keevo.clientportal.auth.domain.port.out.PortalSessionRepository.Credentials;
import com.keevo.identity.api.ClientPortalCodePort;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 21.7 — sign-in of the end clients of the client portal (Façade):
 * <ul>
 *   <li>code request / check by {@link ClientPortalCodePort} (19.2 machinery, portal purpose: a portal code never opens
 *       a merchant session and vice versa; one SMS budget per phone);</li>
 *   <li>a checked code opens a session: the client row is upserted by phone, a 256-bit token is generated and only its
 *       SHA-256 stored, valid {@code keevo.portal.session-ttl} (24 h, Bref's client token), the client's sessions
 *       beyond the {@value #MAX_SESSIONS} newest deleted — one short transaction after the code's own;</li>
 *   <li>each portal request is authenticated by the token (shape check, then one primary-key read), limited per
 *       session and per failing IP ({@link PortalRateLimiter}); sign-out deletes the session (same IP brake).</li>
 * </ul>
 * Reprise 11/11/2026 — Bref's client account: {@link #register} (the code once, with a name, a password and an optional
 * e-mail; a phone that already has an account gets its new password — the code proves the phone, and Bref had no
 * other way back for a client who forgot his password — and its other sessions end) and {@link #login} (phone or
 * e-mail + password, no SMS). Both are braked like Bref's {@code login} route (per IP and per identifier, 15 min,
 * before any hash); an unknown identifier costs the same BCrypt check as a wrong password (no account enumeration by
 * timing). Nothing is cached: a deleted session is refused at its next request. Logs carry no phone, e-mail, password
 * nor token.
 */
@Service
public class PortalAuthService {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthService.class);

    static final int MAX_SESSIONS = 10;
    /** BCrypt reads at most 72 bytes: a longer password is refused rather than silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_EMAIL_LENGTH = 254;
    /** The unique index of the e-mails (V84): the only duplicate a registration turns into 409. */
    static final String EMAIL_INDEX = "uq_portal_clients_email";
    /** Prefix of the registration's brake key (apart from the password sign-in's). */
    static final String REGISTER_KEY = "register:";
    static final String BAD_CREDENTIALS = "Numéro, e-mail ou mot de passe incorrect.";
    static final String PASSWORD_TOO_LONG = "Le mot de passe doit contenir entre 8 et 72 caractères.";
    static final String EMAIL_TAKEN = "Cette adresse e-mail est déjà utilisée par un autre client.";
    static final String EMAIL_INVALID = "Adresse e-mail invalide.";
    static final String TOO_MANY_LOGINS = "Trop de tentatives de connexion : réessayez dans quelques minutes.";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
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
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration sessionTtl;
    /** Checked against when the identifier is unknown, so a miss costs one BCrypt like a wrong password. */
    private final String decoyHash;

    public PortalAuthService(ClientPortalCodePort codes, PortalSessionRepository sessions, PortalRateLimiter limiter,
                             PasswordEncoder passwords, PlatformTransactionManager transactionManager, Clock clock,
                             @Value("${keevo.portal.session-ttl:PT24H}") Duration sessionTtl) {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("keevo.portal.session-ttl must be positive");
        }
        this.codes = codes;
        this.sessions = sessions;
        this.limiter = limiter;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.decoyHash = passwords.encode(PortalTokens.generate());
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
        Instant expiresAt = now.plus(sessionTtl);
        tx.executeWithoutResult(status -> {
            UUID client = sessions.upsertClient(verified, now);
            sessions.insertSession(PortalTokens.hash(token), client, now, expiresAt);
            sessions.trimSessions(client, MAX_SESSIONS);
        });
        log.info("PORTAL_SIGN_IN");
        return new Session(token, expiresAt);
    }

    /**
     * Bref's registration ({@code POST /v1/clients/register}): checks the portal code, then stores the client's name,
     * password (BCrypt) and e-mail and opens a session. A phone that already has an account is not refused (Bref:
     * 422): the code proves the phone, so its password is replaced and its other sessions end.
     *
     * @param email optional, lower-cased; {@code null} keeps the one already stored
     * @throws DomainException {@code PORTAL_TOO_MANY_REQUESTS} (429, before the code is read),
     *                         {@code EMAIL_ALREADY_EXISTS} (409, another client's e-mail — told only to the holder of
     *                         a valid code, so nobody can probe which e-mails have an account; the code is then used),
     *                         {@code VALIDATION_FAILED} (422, password over 72 bytes / e-mail malformed), and the code
     *                         errors of {@link ClientPortalCodePort#verifyCode}
     */
    public Session register(String name, String phone, String code, String password, String email, String clientIp) {
        String mail = email(email);
        String e164 = PhoneNumbers.toE164(phone, PhoneNumbers.CAMEROON);
        assertPasswordFits(password);
        // its own bucket: failed password sign-ins on a phone never block that phone's way back (a new code)
        if (!limiter.tryLogin(clientIp, REGISTER_KEY + (e164 != null ? e164 : phone.strip()))) {
            throw tooManyLogins();
        }
        String verified = codes.verifyCode(phone, code, clientIp);
        if (mail != null && sessions.emailTakenByOther(mail, verified)) {
            throw emailTaken();
        }
        String hash = passwords.encode(password);
        String token = PortalTokens.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionTtl);
        try {
            tx.executeWithoutResult(status -> {
                UUID client = sessions.register(verified, name.strip(), mail, hash, now);
                sessions.deleteSessionsOf(client);
                sessions.insertSession(PortalTokens.hash(token), client, now, expiresAt);
            });
        } catch (DuplicateKeyException e) {
            if (!String.valueOf(e.getMessage()).contains(EMAIL_INDEX)) {
                throw e;
            }
            throw emailTaken(); // the e-mail was taken between the check and the write
        }
        log.info("PORTAL_REGISTER");
        return new Session(token, expiresAt);
    }

    /**
     * Bref's password sign-in ({@code POST /v1/clients/login}): {@code identifier} is a phone (normalized like the
     * invoices', {@code +237} for 9 digits) or an e-mail; no code, no SMS.
     *
     * @throws DomainException {@code INVALID_CREDENTIALS} (401: unknown identifier, account without password, wrong
     *                         password — one answer), {@code PORTAL_TOO_MANY_REQUESTS} (429)
     */
    public Session login(String identifier, String password, String clientIp) {
        String key = identifierKey(identifier);
        if (!limiter.tryLogin(clientIp, key != null ? key : identifier.strip())) {
            throw tooManyLogins();
        }
        Optional<Credentials> found = key == null ? Optional.empty()
                : key.indexOf('@') >= 0 ? sessions.findByEmail(key) : sessions.findByPhone(key);
        String hash = found.map(Credentials::passwordHash).orElse(null);
        boolean fits = password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        boolean matches = passwords.matches(fits ? password : "", hash != null ? hash : decoyHash);
        if (hash == null || !fits || !matches) {
            throw DomainException.userFacing(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
        }
        UUID client = found.get().clientId();
        String token = PortalTokens.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionTtl);
        tx.executeWithoutResult(status -> {
            sessions.touchLogin(client, now);
            sessions.insertSession(PortalTokens.hash(token), client, now, expiresAt);
            sessions.trimSessions(client, MAX_SESSIONS);
        });
        log.info("PORTAL_LOGIN");
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

    /**
     * The look-up key of a sign-in identifier: a lower-cased e-mail when it contains {@code @}, else the phone in
     * E.164 ({@code +237} for a 9-digit local number); {@code null} when it is neither.
     */
    public static String identifierKey(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String id = identifier.strip();
        if (id.indexOf('@') >= 0) {
            String mail = id.toLowerCase(Locale.ROOT);
            return EMAIL.matcher(mail).matches() ? mail : null;
        }
        return PhoneNumbers.toE164(id, PhoneNumbers.CAMEROON);
    }

    /** The optional e-mail of a registration, lower-cased; {@code null} when absent. */
    static String email(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String mail = email.strip().toLowerCase(Locale.ROOT);
        if (mail.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(mail).matches()) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, EMAIL_INVALID);
        }
        return mail;
    }

    private static void assertPasswordFits(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, PASSWORD_TOO_LONG);
        }
    }

    private static DomainException emailTaken() {
        return DomainException.userFacing(ErrorCode.EMAIL_ALREADY_EXISTS, EMAIL_TAKEN);
    }

    private static DomainException tooManyLogins() {
        return DomainException.userFacing(ErrorCode.PORTAL_TOO_MANY_REQUESTS, TOO_MANY_LOGINS);
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.PORTAL_SESSION_INVALID, "Portal session missing, invalid or expired");
    }
}
