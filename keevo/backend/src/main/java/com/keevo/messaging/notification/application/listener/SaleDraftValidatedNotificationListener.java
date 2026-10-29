package com.keevo.messaging.notification.application.listener;

import com.keevo.commerce.api.SaleQueryPort;
import com.keevo.commerce.api.SaleView;
import com.keevo.shared.event.catalog.sale.SaleDraftProductsUpgradedEvent;
import com.keevo.shared.event.catalog.sale.SaleManuallyValidatedEvent;
import com.keevo.shared.event.catalog.sale.SalePendingValidationEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.DeliveryFailedException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * SaleDraftValidatedNotificationListener — notifies the owner (push + WhatsApp)
 * when a pending sale containing draft products is manually validated, and also
 * when a sale with draft products is first submitted for validation.
 *
 * <p>Notification body includes: employee name, store name, product names.
 *
 * <p>Async best-effort — sale operations MUST NOT fail due to notification errors.
 *
 * <p>Story 12.4: durable module listeners (async, after commit, publication registry). The tenant is
 * bound from the event by the module-listener interceptor. {@link DeliverOnce}: a replayed publication
 * never sends the push / WhatsApp twice (receipt per listener and event). Ruling R2: a provider failure
 * on a send is rethrown as {@link DeliveryFailedException} once both channels were attempted, so the
 * publication stays incomplete and is retried (the receipt is released; on a partial failure the retry may
 * repeat the channel that succeeded). Missing sale / owner phone / disabled channel → return normally.
 */
@Component
public class SaleDraftValidatedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(SaleDraftValidatedNotificationListener.class);

    private final SaleQueryPort saleQueryPort;
    private final EmployeeQueryPort employeeQuery;
    private final StoreQueryPort storeQuery;
    private final UserQueryPort userQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final TenantPreferencesQueryPort preferencesQuery;
    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;

    public SaleDraftValidatedNotificationListener(
            SaleQueryPort saleQueryPort,
            EmployeeQueryPort employeeQuery,
            StoreQueryPort storeQuery,
            UserQueryPort userQuery,
            OwnerRecipientResolver ownerRecipientResolver,
            TenantPreferencesQueryPort preferencesQuery,
            PushNotifier pushNotifier,
            WhatsAppSender whatsAppSender) {
        this.saleQueryPort = saleQueryPort;
        this.employeeQuery = employeeQuery;
        this.storeQuery = storeQuery;
        this.userQuery = userQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.preferencesQuery = preferencesQuery;
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
    }

    /**
     * Fires after an owner manually validates a pending sale.
     * Sends push + WhatsApp notification to the owner with employee, store and product details.
     */
    @ApplicationModuleListener
    @DeliverOnce
    public void onSaleManuallyValidated(SaleManuallyValidatedEvent event) {
        log.info("[SALE-VALIDATED] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            String tenant = event.tenantId();

            SaleView sale = saleQueryPort.findById(tenant, event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-VALIDATED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            // Resolve employee display name
            String employeeName = employeeQuery.findByUserId(tenant, sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            // Resolve store name
            String storeName = storeQuery.findById(tenant, sale.storeId())
                    .map(StoreView::name)
                    .orElse("Boutique");

            // Collect product names from sale items
            String productNames = sale.items().stream()
                    .map(item -> item.productName())
                    .collect(Collectors.joining(", "));

            String title = "✅ Vente validée";
            String body = employeeName + " — " + storeName + " : " + productNames;
            String deepLink = "/pos/sales-history/" + event.saleId();

            NotificationPayload payload = NotificationPayload.of(
                    "SALE_DRAFT_VALIDATED",
                    title,
                    body,
                    deepLink,
                    Map.of("saleId", event.saleId().toString(),
                            "storeId", sale.storeId().toString())
            );

            // Resolve channel preference (falls back to PUSH if prefs not found)
            StockAlertChannel channel = preferencesQuery
                    .find(tenant)
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            // ── Push notification ────────────────────────────────────────────
            DeliveryFailedException failure = null;
            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    pushNotifier.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-VALIDATED] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                    failure = new DeliveryFailedException("push send failed", e);
                }
            }

            // ── WhatsApp notification ────────────────────────────────────────
            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppSender.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-VALIDATED] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                        failure = addFailure(failure, new DeliveryFailedException("WhatsApp send failed", e));
                    }
                }
            }
            // Story 12.4 (R2): a provider failure is retried by the registry (receipt released)
            if (failure != null) {
                throw failure;
            }

        } catch (DeliveryFailedException e) {
            throw e;
        } catch (Exception e) {
            // Best-effort — sale validation MUST NOT fail due to notification errors
            log.warn("[SALE-VALIDATED] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        }
    }

    /**
     * Fires when an employee submits a sale containing draft products (PENDING_VALIDATION).
     * Notifies the owner so they can review and validate the pending sale.
     */
    @ApplicationModuleListener
    @DeliverOnce
    public void onSalePendingValidation(SalePendingValidationEvent event) {
        log.info("[SALE-PENDING] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            String tenant = event.tenantId();

            SaleView sale = saleQueryPort.findById(tenant, event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-PENDING] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            String employeeName = employeeQuery.findByUserId(tenant, sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            String storeName = storeQuery.findById(tenant, sale.storeId())
                    .map(StoreView::name)
                    .orElse("Boutique");

            String productNames = sale.items().stream()
                    .map(item -> item.productName())
                    .collect(Collectors.joining(", "));

            String title = "🔶 Vente en attente de validation";
            String body = employeeName + " — " + storeName + " : " + productNames
                    + " — Validez la vente pour activer les produits brouillons";
            // AC7: router-valid path — /pos/pending/{id} navigates to PendingSaleDetailPage
            String deepLink = "/pos/pending/" + event.saleId();

            NotificationPayload payload = NotificationPayload.of(
                    "SALE_PENDING_DRAFT_PRODUCTS",
                    title,
                    body,
                    deepLink,
                    Map.of("saleId", event.saleId().toString(),
                            "storeId", event.storeId().toString())
            );

            StockAlertChannel channel = preferencesQuery
                    .find(tenant)
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            DeliveryFailedException failure = null;
            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    pushNotifier.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-PENDING] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                    failure = new DeliveryFailedException("push send failed", e);
                }
            }

            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppSender.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-PENDING] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                        failure = addFailure(failure, new DeliveryFailedException("WhatsApp send failed", e));
                    }
                }
            }
            // Story 12.4 (R2): a provider failure is retried by the registry (receipt released)
            if (failure != null) {
                throw failure;
            }

        } catch (DeliveryFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SALE-PENDING] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        }
    }

    /**
     * Fires when an employee finalises a sale that contained originally-draft products
     * (new flow — stock entered at checkout, sale recorded as COMPLETED immediately).
     * Notifies the owner: {@code draftsPromoted} (OWNER seller) → products were activated;
     * otherwise (EMPLOYEE seller, v1s-12-10) → drafts were sold and await validation.
     */
    @ApplicationModuleListener
    @DeliverOnce
    public void onSaleDraftProductsUpgraded(SaleDraftProductsUpgradedEvent event) {
        log.info("[SALE-DRAFT-UPGRADED] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            String tenant = event.tenantId();

            SaleView sale = saleQueryPort.findById(tenant, event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-DRAFT-UPGRADED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            // v1s-12-10: an EMPLOYEE seller no longer promotes drafts (promotion is OWNER-only),
            // so the owner is told the drafts await validation instead of "activated". The
            // seller's role travels on the event (draftsPromoted) — never inferred from the
            // Employee row (an owner may have one; an employee's row may be missing).
            boolean draftsPromoted = event.draftsPromoted();
            String employeeName = employeeQuery.findByUserId(tenant, sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            String storeName = storeQuery.findById(tenant, sale.storeId())
                    .map(StoreView::name)
                    .orElse("Boutique");

            String productNames = sale.items().stream()
                    .filter(item -> event.originalDraftProductIds().contains(item.productId()))
                    .map(item -> item.productName())
                    .collect(Collectors.joining(", "));

            String title = draftsPromoted ? "🆕 Nouveaux produits activés" : "🆕 Produits brouillons vendus";
            String body = employeeName + " — " + storeName + " : " + productNames
                    + (draftsPromoted
                        ? " (produits brouillons activés lors d'une vente)"
                        : " (brouillons à valider)");
            String deepLink = "/pos/sales-history/" + event.saleId();

            NotificationPayload payload = NotificationPayload.of(
                    "SALE_DRAFT_PRODUCTS_UPGRADED",
                    title,
                    body,
                    deepLink,
                    Map.of("saleId", event.saleId().toString(),
                            "storeId", event.storeId().toString())
            );

            StockAlertChannel channel = preferencesQuery
                    .find(tenant)
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            DeliveryFailedException failure = null;
            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    pushNotifier.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-DRAFT-UPGRADED] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                    failure = new DeliveryFailedException("push send failed", e);
                }
            }

            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppSender.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-DRAFT-UPGRADED] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                        failure = addFailure(failure, new DeliveryFailedException("WhatsApp send failed", e));
                    }
                }
            }
            // Story 12.4 (R2): a provider failure is retried by the registry (receipt released)
            if (failure != null) {
                throw failure;
            }

        } catch (DeliveryFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SALE-DRAFT-UPGRADED] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        }
    }

    private static DeliveryFailedException addFailure(DeliveryFailedException first, DeliveryFailedException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
