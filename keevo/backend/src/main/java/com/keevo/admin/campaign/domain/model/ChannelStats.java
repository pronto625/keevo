package com.keevo.admin.campaign.domain.model;

/** Story 21.6 — what a platform campaign reached on one channel. */
public record ChannelStats(Channel channel, int targeted, int sent, int delivered, int read, int failed, int skipped,
                           int uncertain) {
}
