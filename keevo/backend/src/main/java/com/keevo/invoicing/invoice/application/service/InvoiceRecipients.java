package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Story 20.8 — the recipient of a document sent by SMS or email: the number / address typed by the user, else the
 * client's (document snapshot), checked per channel with French errors, and its masked form for the audit and the API
 * (never the full recipient outside the module).
 */
public final class InvoiceRecipients {

    /**
     * {@code local@domain.tld}: local part of RFC 5322 « atext » characters in dot-separated words (no {@code <>",;},
     * no space or control character, no leading / trailing / double dot); domain of labels of letters, digits and
     * inner hyphens (no empty label, no leading / trailing hyphen, ≤ 63 characters), a top-level label of ≥ 2
     * letters; at most 255 characters (the column).
     */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
            + "(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
            + "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
    static final int EMAIL_MAX = 255;

    private InvoiceRecipients() {
    }

    /** The override when typed, else the client's value ({@code null} when neither). */
    public static String pick(String override, String fromClient) {
        return override != null && !override.isBlank() ? override : fromClient;
    }

    /**
     * SMS number, E.164 (a 9-digit Cameroonian number gets {@code +237}).
     *
     * @throws DomainException {@code INVOICE_SMS_PHONE_REQUIRED} when blank, {@code INVOICE_SMS_PHONE_INVALID} when malformed
     */
    public static String smsPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.INVOICE_SMS_PHONE_REQUIRED, "No SMS number");
        }
        String phone = PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON);
        if (phone == null) {
            throw new DomainException(ErrorCode.INVOICE_SMS_PHONE_INVALID, "Invalid SMS number");
        }
        return phone;
    }

    /**
     * Email address, trimmed, domain lower-cased.
     *
     * @throws DomainException {@code INVOICE_EMAIL_REQUIRED} when blank, {@code INVOICE_EMAIL_INVALID} when malformed
     */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.INVOICE_EMAIL_REQUIRED, "No email address");
        }
        String email = raw.strip();
        if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            throw new DomainException(ErrorCode.INVOICE_EMAIL_INVALID, "Invalid email address");
        }
        int at = email.lastIndexOf('@');
        return email.substring(0, at + 1) + email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Masked recipient: an email keeps its first character and its domain ({@code compta@ndolo.cm} →
     * {@code c***@ndolo.cm}); a phone as {@link InvoicePhones#mask}. Null stays null.
     */
    public static String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        int at = recipient.lastIndexOf('@');
        if (at > 0) {
            return recipient.charAt(0) + "***" + recipient.substring(at);
        }
        return InvoicePhones.mask(recipient);
    }
}
