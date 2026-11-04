package com.keevo.messaging.channel.domain.port.out;

import com.keevo.messaging.channel.domain.model.SendOutcome;

import com.keevo.shared.capability.notification.EmailMessage;

/** Story 19.9 — one email provider of the failover chain (Strategy), see {@link SmsProvider}. */
public interface EmailProvider {

    /** Configuration name ({@code resend}, {@code infobip}), lower case. */
    String name();

    /** False when its keys are missing: the chain skips it without any call. */
    boolean isConfigured();

    /**
     * Sends one email. Logs its own failure at WARN (provider name and HTTP status only).
     *
     * @param sender RFC 5322 sender: {@code address} or {@code "Name" <address>}
     * @return {@link SendOutcome#DELIVERED} on HTTP 2xx; {@link SendOutcome#UNKNOWN} on a read timeout
     */
    SendOutcome send(EmailMessage message, String sender);
}
