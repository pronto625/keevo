package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — an SMS to send.
 *
 * @param to       recipient phone, E.164 ({@code +237…}); spaces, dashes and dots are stripped before checking
 * @param text     message text
 * @param senderId optional alphanumeric sender id (non-alphanumerics stripped, at most 11 characters); the configured
 *                 default ({@code keevo.sms.sender-id}) when null or empty after stripping
 */
public record SmsMessage(String to, String text, String senderId) {

    public static SmsMessage of(String to, String text) {
        return new SmsMessage(to, text, null);
    }
}
