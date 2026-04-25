package com.keevo.messaging.notification.application.strategy;

import com.keevo.catalog.stock.domain.event.TransferCreatedEvent;

import java.util.List;
import java.util.UUID;

/**
 * GoF Strategy — recipient selection for transfer-created notifications.
 *
 * <p>Different transfer types (or future multi-store topologies) may require
 * different recipient sets without modifying the listener.
 *
 * Story HF-2 AC2.
 */
public interface TransferNotificationRecipientStrategy {

    /**
     * Resolve the list of user IDs to notify for the given transfer event.
     *
     * @param event the transfer-created event
     * @return list of user UUIDs; empty list suppresses notification
     */
    List<UUID> resolveRecipients(TransferCreatedEvent event);
}
