package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.OtpIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Story 19.2 — sign-in code settings ({@code keevo.auth.otp.*}): allowed phone prefixes and the store-review account.
 *
 * <p>Store-review account (Apple, Google, Meta reviewers, Bref's {@code REVIEW_ACCOUNT_*}): a fixed identifier and code
 * set by the environment only; disabled when either is empty or the code is not at least 6 digits. Its code
 * requests send nothing and skip the limits; its code is compared in constant time.
 */
@Component
public class OtpSettings {

    private static final Logger log = LoggerFactory.getLogger(OtpSettings.class);

    /** Validity of a code. */
    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** Wrong codes that kill a challenge. */
    public static final int MAX_ATTEMPTS = 5;
    /** Wrong codes on one identifier within {@link #DAILY_WINDOW} that block requests and checks. */
    public static final int DAILY_MAX_FAILURES = 20;
    public static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final List<String> allowedPhonePrefixes;
    private final String reviewIdentifier;
    private final byte[] reviewCode;

    public OtpSettings(
            @Value("${keevo.auth.otp.allowed-phone-prefixes:237,241,235,243,221}") String allowedPhonePrefixes,
            @Value("${keevo.auth.otp.review-identifier:}") String reviewIdentifier,
            @Value("${keevo.auth.otp.review-code:}") String reviewCode) {
        List<String> prefixes = Arrays.stream(String.valueOf(allowedPhonePrefixes).split(","))
                .map(p -> p.replaceAll("\\D", ""))
                .filter(p -> !p.isEmpty())
                .toList();
        this.allowedPhonePrefixes = prefixes.isEmpty() ? List.of("237", "241", "235", "243", "221") : prefixes;

        String identifier = OtpIdentifier.normalize(reviewIdentifier);
        String code = reviewCode == null ? "" : reviewCode.strip();
        boolean enabled = identifier != null && code.matches("\\d{6,}");
        if (!enabled && reviewIdentifier != null && !reviewIdentifier.isBlank()) {
            log.warn("OTP review account disabled: identifier or code (at least 6 digits) invalid");
        }
        this.reviewIdentifier = enabled ? identifier : null;
        this.reviewCode = enabled ? code.getBytes(StandardCharsets.UTF_8) : null;
    }

    public List<String> allowedPhonePrefixes() {
        return allowedPhonePrefixes;
    }

    /** True when the review account is enabled and the identifier is its identifier. */
    public boolean isReviewIdentifier(OtpIdentifier identifier) {
        return reviewIdentifier != null && reviewIdentifier.equals(identifier.value());
    }

    /** True when the review account is enabled, the identifier is its identifier and the code matches (constant time). */
    public boolean isReviewLogin(OtpIdentifier identifier, String code) {
        return isReviewIdentifier(identifier) && code != null
                && MessageDigest.isEqual(reviewCode, code.strip().getBytes(StandardCharsets.UTF_8));
    }
}
