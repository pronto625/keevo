package com.keevo.payments.appstore.application.service;

import com.keevo.payments.appstore.domain.model.AppStoreEvent;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.RevenueCatUnavailableException;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.StoreSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.2 — entry of the RevenueCat webhook: the {@code Authorization} header must equal the configured secret
 * (constant-time comparison; an empty secret or header never matches), then the event is processed in one transaction
 * ({@link AppStoreSubscriptionService#process}). A TRANSFER first reads RevenueCat for its customers (never inside the
 * transaction); an already processed event skips that call.
 */
@Service
public class AppStoreWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AppStoreWebhookService.class);

    /** What the controller answers. */
    public enum Result { OK, UNAUTHORIZED, RETRY }

    private final AppStoreSubscriptionService subscriptions;
    private final ExternalSubscriptionStore store;
    private final RevenueCatClient revenueCat;
    private final AppStoreSettings settings;

    public AppStoreWebhookService(AppStoreSubscriptionService subscriptions, ExternalSubscriptionStore store,
                                  RevenueCatClient revenueCat, AppStoreSettings settings) {
        this.subscriptions = subscriptions;
        this.store = store;
        this.revenueCat = revenueCat;
        this.settings = settings;
    }

    public Result handle(String authorization, Map<String, Object> body) {
        if (!authorized(authorization)) {
            log.warn("[AppStore] RevenueCat webhook refused: invalid secret");
            return Result.UNAUTHORIZED;
        }
        Optional<AppStoreEvent> parsed = AppStoreEvent.parse(body);
        if (parsed.isEmpty()) {
            log.warn("[AppStore] RevenueCat webhook without a usable event: ignored");
            return Result.OK;
        }
        AppStoreEvent event = parsed.get();
        Map<UUID, List<StoreSubscription>> transfers = Map.of();
        if ("TRANSFER".equals(event.type())) {
            if (store.eventExists(event.id())) {
                return Result.OK;
            }
            try {
                transfers = readCustomers(event);
            } catch (RevenueCatUnavailableException e) {
                log.warn("[AppStore] TRANSFER not processed, RevenueCat unavailable: redelivery expected");
                return Result.RETRY;
            }
        }
        EventOutcome outcome = subscriptions.process(event, transfers);
        if (outcome == EventOutcome.DUPLICATE) {
            log.info("[AppStore] RevenueCat event already processed");
        }
        return Result.OK;
    }

    /** RevenueCat subscriptions of every customer of a TRANSFER (at most 10 + 10 ids, parsed as user UUIDs). */
    private Map<UUID, List<StoreSubscription>> readCustomers(AppStoreEvent event) {
        Set<UUID> customers = new LinkedHashSet<>();
        for (String id : event.transferredFrom()) {
            AppStoreEvent.uuid(id).ifPresent(customers::add);
        }
        for (String id : event.transferredTo()) {
            AppStoreEvent.uuid(id).ifPresent(customers::add);
        }
        Map<UUID, List<StoreSubscription>> out = new LinkedHashMap<>();
        for (UUID customer : customers) {
            out.put(customer, revenueCat.subscriptions(customer.toString()));
        }
        return out;
    }

    boolean authorized(String header) {
        String secret = settings.webhookSecret();
        if (secret == null || secret.isBlank() || header == null || header.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }
}
