package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.OtpChannel;
import com.keevo.identity.auth.domain.model.OtpIdentifier;
import com.keevo.identity.auth.domain.model.OtpPurpose;
import com.keevo.identity.auth.domain.port.in.RequestOtpCommand;
import com.keevo.identity.auth.domain.port.in.RequestOtpUseCase;
import com.keevo.identity.auth.domain.port.out.OtpChallengeRepository;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.2 — sends a 6-digit sign-in code by SMS, WhatsApp or email (port of Bref's {@code OtpService.requestOtp}).
 *
 * <p>Façade: identifier normalization ({@link OtpIdentifier}) → channel rule → store-review shortcut → limits
 * ({@link OtpRateLimiter}, daily failure cap in the database) → previous live codes consumed → new code stored as a
 * {@link PasswordEncoder} hash (computed before any SQL) → sends once the short database transaction has committed
 * and released its connection.
 *
 * <p>The answer never depends on whether an account exists (no account lookup at all). A delivery failure is logged
 * with the masked identifier and the exception class only, never the code, and never changes the answer. Codes are
 * platform sends: {@link SmsSender} / {@link EmailSender} / {@link WhatsAppSender} directly, never the tenant
 * messaging (no tenant quota).
 *
 * <p>Complexity: O(1) in-memory limit checks, one indexed SUM, one UPDATE, one INSERT, then 1–2 provider calls.
 */
@Service
public class RequestOtpService implements RequestOtpUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestOtpService.class);

    static final int EXPIRES_IN_SECONDS = (int) OtpSettings.CODE_TTL.toSeconds();

    private final OtpSettings settings;
    private final OtpRateLimiter rateLimiter;
    private final OtpChallengeRepository challengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final WhatsAppSender whatsAppSender;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public RequestOtpService(OtpSettings settings,
                             OtpRateLimiter rateLimiter,
                             OtpChallengeRepository challengeRepository,
                             PasswordEncoder passwordEncoder,
                             SmsSender smsSender,
                             EmailSender emailSender,
                             WhatsAppSender whatsAppSender,
                             Clock clock,
                             TransactionTemplate transactionTemplate) {
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.challengeRepository = challengeRepository;
        this.passwordEncoder = passwordEncoder;
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.whatsAppSender = whatsAppSender;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Not {@code @Transactional}: the bcrypt hash is computed before any SQL, only the database part (daily cap,
     * invalidate, insert) runs in a short transaction, and the provider calls run once it has committed and released
     * its connection. Nothing is sent when the database part throws.
     */
    @Override
    public int request(RequestOtpCommand command) {
        OtpIdentifier identifier = OtpIdentifier.parse(command.identifier(), settings.allowedPhonePrefixes());
        return issue(identifier, identifier.channelsFor(command.channel()), OtpPurpose.SIGN_IN, command.clientIp(),
                command.language());
    }

    /**
     * Story 21.7 — the code issue shared by every purpose (Template Method core): the challenge is stored under
     * {@link OtpPurpose#challengeKey} (a code of one purpose never verifies under another, each purpose has its own
     * daily failure cap), while the request limits count the identifier itself (one SMS budget per phone, whatever
     * the purpose).
     *
     * @return validity of the code in seconds
     */
    public int issue(OtpIdentifier identifier, Set<OtpChannel> channels, OtpPurpose purpose, String clientIp,
                     String language) {
        // Store-review account: no limit, nothing stored, nothing sent (its fixed code is checked at verify).
        if (settings.isReviewIdentifier(identifier)) {
            log.warn("OTP request for the store-review account ({})", identifier.masked());
            return EXPIRES_IN_SECONDS;
        }

        rateLimiter.acquire(identifier.value(), clientIp);
        String key = purpose.challengeKey(identifier);
        String code = generateCode();
        String codeHash = passwordEncoder.encode(code);

        transactionTemplate.executeWithoutResult(status -> {
            Instant now = clock.instant();
            int failures = challengeRepository.sumFailuresSince(key, now.minus(OtpSettings.DAILY_WINDOW));
            if (failures >= OtpSettings.DAILY_MAX_FAILURES) {
                log.warn("OTP blocked for {}: {} failed codes in 24 h", identifier.masked(), failures);
                throw new DomainException(ErrorCode.OTP_TOO_MANY_REQUESTS);
            }
            challengeRepository.invalidateActive(key, now);
            challengeRepository.insert(UUID.randomUUID(), key, codeHash, now.plus(OtpSettings.CODE_TTL), now);
        });

        send(identifier, channels, code, OtpMessages.resolve(language));
        return EXPIRES_IN_SECONDS;
    }

    /** 6 digits, 100000–999999. */
    String generateCode() {
        return Integer.toString(100_000 + secureRandom.nextInt(900_000));
    }

    private void send(OtpIdentifier identifier, Set<OtpChannel> channels, String code, OtpMessages.Lang lang) {
        // Fixed order: SMS before WhatsApp (EnumSet order).
        for (OtpChannel channel : List.copyOf(channels)) {
            try {
                Boolean delivered = switch (channel) {
                    case SMS -> deliver(smsSender.send(SmsMessage.of(identifier.value(), OtpMessages.sms(lang, code))));
                    case EMAIL -> deliver(emailSender.send(new EmailMessage(identifier.value(),
                            OtpMessages.emailSubject(lang), OtpMessages.emailText(lang, code),
                            OtpMessages.emailHtml(lang, code), null, List.of())));
                    case WHATSAPP -> sendWhatsApp(identifier, code, lang);
                };
                if (delivered == null) {
                    continue; // WhatsApp: no delivery result (logged in sendWhatsApp)
                }
                if (delivered) {
                    log.info("OTP sent by {} to {}", channel, identifier.masked());
                } else {
                    log.error("OTP not delivered by {} to {}", channel, identifier.masked());
                }
            } catch (RuntimeException e) {
                // Exception class only: provider messages can embed the recipient.
                log.error("OTP send by {} failed for {}: {}", channel, identifier.masked(), e.getClass().getSimpleName());
            }
        }
    }

    private static Boolean deliver(DeliveryResult result) {
        return result != null && result.delivered();
    }

    /**
     * Story 19.9b — Meta template {@code bref_point_otp} first, the localized text as fallback. Without a real
     * provider (no-op stub) nothing is handed over.
     */
    private Boolean sendWhatsApp(OtpIdentifier identifier, String code, OtpMessages.Lang lang) {
        if (!whatsAppSender.canSendCodes()) {
            log.debug("OTP WhatsApp skipped for {}: no WhatsApp provider", identifier.masked());
            return null;
        }
        whatsAppSender.sendCode(identifier.value(), code, OtpMessages.sms(lang, code),
                lang == OtpMessages.Lang.EN ? "en" : "fr");
        // sendCode gives no delivery result: the message was handed over, not confirmed.
        log.info("OTP handed to WhatsApp provider for {}", identifier.masked());
        return null;
    }
}
