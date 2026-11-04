package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.OtpChannel;

/**
 * Story 19.2 — sign-in code request.
 *
 * @param identifier raw email or phone as typed
 * @param channel    requested channel, null = default (EMAIL for an email, SMS + WhatsApp for a phone)
 * @param clientIp   caller address for the per-IP limit (null = not limited by IP)
 * @param language   {@code Accept-Language} value (null = French)
 */
public record RequestOtpCommand(String identifier, OtpChannel channel, String clientIp, String language) {}
