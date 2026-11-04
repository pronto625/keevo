package com.keevo.messaging.channel.domain.port.out;

import com.keevo.messaging.channel.domain.model.SendOutcome;

/**
 * Story 19.9 — one SMS provider of the failover chain (Strategy). Adding a provider = one adapter + its name in
 * {@code keevo.sms.providers}; callers are unchanged.
 */
public interface SmsProvider {

    /** Configuration name ({@code mboasms}, {@code infobip}), lower case. */
    String name();

    /** False when its keys are missing: the chain skips it without any call. */
    boolean isConfigured();

    /**
     * Sends one SMS. Logs its own failure at WARN (provider name and HTTP status only).
     *
     * @param phone    normalized E.164 phone
     * @param senderId normalized sender id (alphanumeric, at most 11 characters)
     * @return {@link SendOutcome#DELIVERED} only on provider acceptance (HTTP 2xx, not rejected);
     *         {@link SendOutcome#UNKNOWN} when the request went out but no answer came (read timeout)
     */
    SendOutcome send(String phone, String text, String senderId);
}
