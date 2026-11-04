package com.keevo.messaging.channel.adapter.out.config;

import java.util.List;

/**
 * Story 19.9 — {@code keevo.sms.*}: provider failover order (empty = channel disabled), default sender id, provider
 * keys.
 */
public record SmsChannelProperties(List<String> providers, String senderId, ProviderKeys mboasms,
                                   ProviderKeys infobip) {

    public SmsChannelProperties {
        providers = List.copyOf(providers);
    }
}
