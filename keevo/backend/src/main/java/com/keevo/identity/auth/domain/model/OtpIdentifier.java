package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Story 19.2 — identifier a sign-in code is sent to: an email address (lowercased) or an E.164 phone of an allowed
 * country. Value Object: the single place of Bref's normalization ({@code api-bref/src/otp/identifier.ts}).
 *
 * <p>Phone: {@link PhoneNumbers#normalize}, then a leading {@code +00} becomes {@code +} (international prefix typed as
 * {@code 00}), then {@code ^\+\d{8,15}$}; a valid phone whose dialing prefix is not allowed is refused with
 * {@code PHONE_COUNTRY_NOT_SUPPORTED}. Email: contains {@code @}, at most 254 characters, Bref's regex. Anything else
 * is {@code VALIDATION_FAILED}. {@link #toString()} is masked (never the full identifier in logs).
 */
public final class OtpIdentifier {

    public enum Kind { PHONE, EMAIL }

    static final int EMAIL_MAX = 254;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+\\d{8,15}$");

    private final String value;
    private final Kind kind;

    private OtpIdentifier(String value, Kind kind) {
        this.value = value;
        this.kind = kind;
    }

    /**
     * Parses and normalizes a raw identifier.
     *
     * @param allowedPhonePrefixes dialing prefixes without {@code +} (e.g. {@code 237})
     * @throws DomainException VALIDATION_FAILED (neither an email nor a phone) or PHONE_COUNTRY_NOT_SUPPORTED
     */
    public static OtpIdentifier parse(String raw, Collection<String> allowedPhonePrefixes) {
        String normalized = normalize(raw);
        if (normalized == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "invalid identifier");
        }
        if (normalized.indexOf('@') >= 0) {
            return new OtpIdentifier(normalized, Kind.EMAIL);
        }
        boolean allowed = allowedPhonePrefixes != null
                && allowedPhonePrefixes.stream().anyMatch(p -> p != null && !p.isBlank()
                        && normalized.startsWith("+" + p.strip()));
        if (!allowed) {
            throw new DomainException(ErrorCode.PHONE_COUNTRY_NOT_SUPPORTED);
        }
        return new OtpIdentifier(normalized, Kind.PHONE);
    }

    /**
     * Normalized form of a well-formed identifier (lowercased email, E.164 phone) without the country check, or null
     * when it is neither. Used to compare against a configured identifier (store-review account).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.indexOf('@') >= 0) {
            return value.length() <= EMAIL_MAX && EMAIL.matcher(value).matches()
                    ? value.toLowerCase(Locale.ROOT) : null;
        }
        String phone = PhoneNumbers.normalize(value);
        if (phone.startsWith("+00")) {
            phone = "+" + phone.substring(3);
        }
        return PHONE.matcher(phone).matches() ? phone : null;
    }

    /**
     * Channels a code is sent through: an email gets EMAIL only; a phone gets the requested channel, or SMS and
     * WhatsApp when none is requested (Bref behaviour).
     *
     * @throws DomainException VALIDATION_FAILED when the channel does not fit the identifier
     */
    public Set<OtpChannel> channelsFor(OtpChannel requested) {
        if (kind == Kind.EMAIL) {
            if (requested != null && requested != OtpChannel.EMAIL) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "channel does not match the identifier");
            }
            return EnumSet.of(OtpChannel.EMAIL);
        }
        if (requested == OtpChannel.EMAIL) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "channel does not match the identifier");
        }
        return requested == null ? EnumSet.of(OtpChannel.SMS, OtpChannel.WHATSAPP) : EnumSet.of(requested);
    }

    public String value() { return value; }
    public Kind kind()    { return kind; }
    public boolean isPhone() { return kind == Kind.PHONE; }
    public boolean isEmail() { return kind == Kind.EMAIL; }

    /** Masked form for logs: {@code +2376******22}, {@code a***@ex.com}. */
    public String masked() {
        if (kind == Kind.EMAIL) {
            int at = value.indexOf('@');
            return value.charAt(0) + "***" + value.substring(at);
        }
        int keepStart = Math.min(5, value.length());
        int keepEnd = Math.max(keepStart, value.length() - 2);
        return value.substring(0, keepStart) + "*".repeat(keepEnd - keepStart) + value.substring(keepEnd);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OtpIdentifier other && value.equals(other.value);
    }

    @Override
    public int hashCode() { return Objects.hash(value); }

    @Override
    public String toString() { return masked(); }
}
