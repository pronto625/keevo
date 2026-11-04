package com.keevo.messaging.channel.adapter.out.config;

import java.util.List;

/**
 * Story 19.9 — {@code keevo.email.*}: provider failover order (empty = channel disabled), sender address and display
 * name, provider keys (Resend has a fixed URL: only its key).
 */
public record EmailChannelProperties(List<String> providers, String from, String fromName, String resendApiKey,
                                     ProviderKeys infobip) {

    public EmailChannelProperties {
        providers = List.copyOf(providers);
        from = from == null ? "" : from.strip();
        resendApiKey = resendApiKey == null ? "" : resendApiKey.strip();
    }
}
