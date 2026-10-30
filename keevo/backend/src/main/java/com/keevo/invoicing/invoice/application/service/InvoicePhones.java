package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.regex.Pattern;

/**
 * WhatsApp number of an invoice's client (Story 14.2): canonical E.164 ({@link PhoneNumbers#normalize}), a 9-digit
 * Cameroonian number typed without its country code gets {@code +237} ({@code 677 00 01 11} → {@code +237677000111}),
 * and a masked form for audit / API (« +237 6•• ••• 111 »).
 */
public final class InvoicePhones {

    static final String CAMEROON = "+237";
    private static final Pattern LOCAL_CM = Pattern.compile("^[0-9]{9}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final char DOT = '•';

    private InvoicePhones() {
    }

    /**
     * @return the E.164 number
     * @throws DomainException {@code INVOICE_PHONE_REQUIRED} when blank, {@code INVOICE_PHONE_INVALID} when malformed
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.INVOICE_PHONE_REQUIRED, "No WhatsApp number");
        }
        String cleaned = raw.strip().replaceAll("[\\s\\-.()]", "");
        String phone;
        if (cleaned.startsWith("+")) {
            phone = cleaned;
        } else if (cleaned.startsWith("00")) {
            phone = "+" + cleaned.substring(2);
        } else if (LOCAL_CM.matcher(cleaned).matches()) {
            phone = CAMEROON + cleaned;
        } else {
            phone = PhoneNumbers.normalize(cleaned);
        }
        if (!E164.matcher(phone).matches()) {
            throw new DomainException(ErrorCode.INVOICE_PHONE_INVALID, "Invalid WhatsApp number");
        }
        return phone;
    }

    /**
     * {@code +237677000111} → {@code +237 6•• ••• 111}; another country keeps {@code +} and its first 2 digits only:
     * {@code +33612345678} → {@code +33 ••• 678}, {@code +12025550123} → {@code +12 ••• 123}. Null stays null.
     */
    public static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String last3 = phone.length() > 3 ? phone.substring(phone.length() - 3) : phone;
        if (phone.startsWith(CAMEROON) && phone.length() == CAMEROON.length() + 9) {
            char first = phone.charAt(CAMEROON.length());
            return CAMEROON + " " + first + DOT + DOT + " " + DOT + DOT + DOT + " " + last3;
        }
        String prefix = phone.startsWith("+") && phone.length() > 6 ? phone.substring(0, 3) : "+";
        return prefix + " " + DOT + DOT + DOT + " " + last3;
    }
}
