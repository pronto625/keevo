package com.keevo.messaging.notification.domain.port.out;

import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;

import java.util.List;

/**
 * Story 19.9 — one push delivery channel (Expo, FCM, the logging stub; Web Push in 19-9b), internal to messaging.
 * {@code PersistingPushNotifier} reads the recipients' tokens once per event, gives each token to the first channel
 * that {@link #supports} it, and calls each channel with its own tokens. Best effort: {@link #send} never throws.
 */
public interface PushDeliveryChannel {

    /** True when this channel delivers this kind of token. */
    boolean supports(String token);

    /** True when the channel can really deliver (configured and initialised). */
    boolean isAvailable();

    /**
     * Sends to these tokens (all supported by this channel). Token cleanup runs in {@code tenantSchema}: the channel
     * sets {@code TenantContext} and clears it; the caller restores its own.
     */
    PushSendResult send(String tenantSchema, List<String> tokens, NotificationPayload payload);
}
