package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

/**
 * WhatsApp number of an invoice's client (Story 14.2): canonical E.164 ({@link PhoneNumbers#toE164}), a 9-digit
 * Cameroonian number typed without its country code gets {@code +237} ({@code 677 00 01 11} → {@code +237677000111}),
 * and a masked form for audit / API (« +237 6•• ••• 111 »).
 */
public final class InvoicePhones {

    static final String CAMEROON = PhoneNumbers.CAMEROON;
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
        String phone = PhoneNumbers.toE164(raw, CAMEROON); // Story 20.2: one normalization shared with the crm
        if (phone == null) {
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
