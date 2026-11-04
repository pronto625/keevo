package com.keevo.shared.capability.notification;

import java.util.List;

/**
 * Story 19.9 — an email to send.
 *
 * @param to          recipient address
 * @param subject     subject line
 * @param text        plain-text body
 * @param html        optional HTML body
 * @param fromName    optional display name of the sender (the store name); the configured address stays the sender
 * @param attachments files, never null (empty list when none)
 */
public record EmailMessage(String to, String subject, String text, String html, String fromName,
                           List<EmailAttachment> attachments) {

    public EmailMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static EmailMessage of(String to, String subject, String text) {
        return new EmailMessage(to, subject, text, null, null, List.of());
    }
}
