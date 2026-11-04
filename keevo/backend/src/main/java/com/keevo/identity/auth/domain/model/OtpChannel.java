package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Locale;

/** Story 19.2 — channel a sign-in code is sent through. */
public enum OtpChannel {
    SMS, WHATSAPP, EMAIL;

    /**
     * Parses the optional {@code channel} field of a code request.
     *
     * @return null when absent or blank (default channels apply)
     * @throws DomainException VALIDATION_FAILED for an unknown value
     */
    public static OtpChannel parseOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "unknown channel");
        }
    }
}
