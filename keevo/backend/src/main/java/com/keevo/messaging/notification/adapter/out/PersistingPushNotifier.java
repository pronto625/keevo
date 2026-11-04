package com.keevo.messaging.notification.adapter.out;

import com.keevo.identity.api.UserQueryPort;
import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.messaging.notification.domain.port.out.PushDeliveryChannel;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Story 19.9 — the single {@code @Primary} {@link PushNotifier}: a Decorator that writes one inbox row per recipient
 * ({@code notifications}), then a Composite over the {@link PushDeliveryChannel}s (Expo for {@code ExponentPushToken[…]}
 * tokens, FCM — or the logging stub — for the others). Every existing business notification thus lands in the inbox
 * with no change to its caller. Story 19.9b — then Web Push ({@link WebPushSender}) to the recipients' browsers: keyed
 * by user, not by device token, so a path of its own after the device tokens.
 *
 * <p>Per event: recipients resolved once (1 owner-id query for {@link #notifyOwners}, in the inbox transaction); inbox =
 * 1 JDBC batch insert in its own short transaction (a failure never reaches the caller's transaction nor the push: logged at ERROR);
 * delivery = 1 token query, each token routed to the first channel that supports it, one {@code send} per channel;
 * Web Push = 1 subscription query + 1 call per browser on {@code notificationExecutor}, only when VAPID keys are set. A Web Push failure never
 * affects the inbox or the device push. {@link #sendToTokens} (global admin broadcast,
 * kept in {@code public.global_notifications}) is not persisted: its tokens are routed the same way and the results
 * summed. Each channel is isolated and best effort; the caller's {@link TenantContext} is restored afterwards.
 */
@Component
@Primary
public class PersistingPushNotifier implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PersistingPushNotifier.class);

    private final NotificationInboxRepository inbox;
    private final UserQueryPort users;
    private final DeviceTokenRepository deviceTokens;
    private final List<PushDeliveryChannel> channels;
    private final WebPushSender webPush;
    private final Executor executor;
    private final TransactionTemplate requiresNew;

    public PersistingPushNotifier(NotificationInboxRepository inbox, UserQueryPort users,
                                  DeviceTokenRepository deviceTokens, List<PushDeliveryChannel> channels,
                                  WebPushSender webPush, PlatformTransactionManager transactionManager,
                                  @Qualifier("notificationExecutor") Executor executor) {
        this.inbox = inbox;
        this.users = users;
        this.deviceTokens = deviceTokens;
        this.channels = List.copyOf(channels);
        this.webPush = webPush;
        this.executor = executor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        log.info("[Push] delivery channels: {}",
                this.channels.stream().map(c -> c.getClass().getSimpleName()).toList());
    }

    /** The delivery channels, in their order (tests). */
    List<PushDeliveryChannel> channels() {
        return channels;
    }

    @Override
    public void notifyOwners(String tenantId, NotificationPayload payload) {
        List<UUID> owners = persistOwners(tenantId, payload);
        deliver(tenantId, payload, deviceTokens::findOwnerTokens);
        webPush(owners, payload);
    }

    @Override
    public void notifyUsers(String tenantId, List<UUID> userIds, NotificationPayload payload) {
        if (userIds == null || userIds.isEmpty()) return;
        List<UUID> recipients = List.copyOf(new LinkedHashSet<>(userIds.stream().filter(Objects::nonNull).toList()));
        if (recipients.isEmpty()) return;
        persist(tenantId, payload, recipients);
        deliver(tenantId, payload, () -> deviceTokens.findByUserIds(recipients));
        webPush(recipients, payload);
    }

    @Override
    public boolean isPushAvailable() {
        return channels.stream().anyMatch(PushDeliveryChannel::isAvailable);
    }

    @Override
    public PushSendResult sendToTokens(String tenantId, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        String previous = TenantContext.getCurrentTenant();
        try {
            return route(tenantId, tokens, payload);
        } finally {
            restore(previous);
        }
    }

    /** The tenant's owners, once per event; empty (logged) when the lookup fails. */
    /**
     * The tenant's owners resolved once and their inbox rows written, both inside the same short {@code REQUIRES_NEW}
     * transaction (a lookup failure never marks the caller's transaction rollback-only). The owners found are
     * returned even when the insert fails (device push and Web Push still go); empty when the lookup fails.
     */
    private List<UUID> persistOwners(String tenantId, NotificationPayload payload) {
        AtomicReference<List<UUID>> owners = new AtomicReference<>(List.of());
        try {
            requiresNew.executeWithoutResult(s -> {
                owners.set(List.copyOf(new LinkedHashSet<>(users.findOwnerUserIds(tenantId))));
                if (!owners.get().isEmpty()) {
                    inbox.insertAll(tenantId, owners.get(), payload);
                }
            });
        } catch (RuntimeException e) {
            log.error("[Inbox] notification not persisted for tenant={} type={} ({})", tenantId, payload.type(),
                    e.getClass().getSimpleName(), e);
        }
        return owners.get();
    }

    private void persist(String tenantId, NotificationPayload payload, List<UUID> recipients) {
        if (recipients.isEmpty()) return;
        try {
            requiresNew.executeWithoutResult(s -> inbox.insertAll(tenantId, recipients, payload));
        } catch (RuntimeException e) {
            log.error("[Inbox] notification not persisted for tenant={} type={} ({})", tenantId, payload.type(),
                    e.getClass().getSimpleName(), e);
        }
    }

    private void deliver(String tenantId, NotificationPayload payload, Supplier<List<DeviceToken>> tokenQuery) {
        String previous = TenantContext.getCurrentTenant();
        try {
            List<String> tokens;
            TenantContext.setCurrentTenant(tenantId);
            try {
                tokens = tokenQuery.get().stream().map(DeviceToken::token).filter(Objects::nonNull).distinct()
                        .toList();
            } catch (RuntimeException e) {
                log.warn("[Push] token lookup failed for tenant={} type={} ({})", tenantId, payload.type(),
                        e.getClass().getSimpleName());
                return;
            }
            if (!tokens.isEmpty()) {
                route(tenantId, tokens, payload);
            }
        } finally {
            restore(previous);
        }
    }

    /** Story 19.9b — the recipients' browsers; isolated: never reaches the caller. */
    /**
     * Story 19.9b — the recipients' browsers, handed to {@code notificationExecutor} (bounded queue, MDC
     * propagation): the caller never waits on push services. Isolated: never reaches the caller.
     */
    private void webPush(List<UUID> recipients, NotificationPayload payload) {
        if (webPush == null || recipients.isEmpty() || !webPush.isEnabled()) return;
        Runnable send = () -> {
            try {
                webPush.sendToUsers(recipients, payload);
            } catch (RuntimeException e) {
                log.warn("[WebPush] failed for type={} ({})", payload.type(), e.getClass().getSimpleName());
            }
        };
        try {
            executor.execute(send);
        } catch (RuntimeException e) {
            log.warn("[WebPush] not dispatched for type={} ({})", payload.type(), e.getClass().getSimpleName());
        }
    }

    /** Each token to the first channel supporting it; tokens no channel supports count as failures. */
    private PushSendResult route(String tenantId, List<String> tokens, NotificationPayload payload) {
        Map<PushDeliveryChannel, List<String>> byChannel = new LinkedHashMap<>();
        int unsupported = 0;
        for (String token : tokens) {
            PushDeliveryChannel channel = channels.stream().filter(c -> c.supports(token)).findFirst().orElse(null);
            if (channel == null) {
                unsupported++;
            } else {
                byChannel.computeIfAbsent(channel, c -> new ArrayList<>()).add(token);
            }
        }
        PushSendResult total = new PushSendResult(0, unsupported, 0, 0);
        for (Map.Entry<PushDeliveryChannel, List<String>> entry : byChannel.entrySet()) {
            PushDeliveryChannel channel = entry.getKey();
            List<String> own = entry.getValue();
            try {
                total = total.plus(channel.send(tenantId, own, payload));
            } catch (RuntimeException e) {
                log.warn("[Push] {} failed for tenant={} type={} ({})", channel.getClass().getSimpleName(), tenantId,
                        payload.type(), e.getClass().getSimpleName());
                total = total.plus(new PushSendResult(0, own.size(), 1, 1));
            }
        }
        return total;
    }

    private static void restore(String previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.setCurrentTenant(previous);
        }
    }
}
