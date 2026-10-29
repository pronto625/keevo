package com.keevo.messaging.notification.application.listener;

import com.keevo.commerce.api.SaleQueryPort;
import com.keevo.commerce.api.SaleView;
import com.keevo.commerce.sale.domain.event.SaleDraftProductsUpgradedEvent;
import com.keevo.commerce.sale.domain.event.SaleManuallyValidatedEvent;
import com.keevo.commerce.sale.domain.event.SalePendingValidationEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.messaging.api.NotificationPayload;
import com.keevo.messaging.api.NotificationPort;
import com.keevo.messaging.api.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

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
    private final NotificationPort notificationPort;
    private final WhatsAppPort whatsAppPort;

    public SaleDraftValidatedNotificationListener(
            SaleQueryPort saleQueryPort,
            EmployeeQueryPort employeeQuery,
            StoreQueryPort storeQuery,
            UserQueryPort userQuery,
            OwnerRecipientResolver ownerRecipientResolver,
            TenantPreferencesQueryPort preferencesQuery,
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort) {
        this.saleQueryPort = saleQueryPort;
        this.employeeQuery = employeeQuery;
        this.storeQuery = storeQuery;
        this.userQuery = userQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.preferencesQuery = preferencesQuery;
        this.notificationPort = notificationPort;
        this.whatsAppPort = whatsAppPort;
    }

    /**
     * Fires after an owner manually validates a pending sale.
     * Sends push + WhatsApp notification to the owner with employee, store and product details.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaleManuallyValidated(SaleManuallyValidatedEvent event) {
        log.info("[SALE-VALIDATED] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

            SaleView sale = saleQueryPort.findById(TenantContext.getCurrentTenant(), event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-VALIDATED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            // Resolve employee display name
            String employeeName = employeeQuery.findByUserId(TenantContext.getCurrentTenant(), sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            // Resolve store name
            String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), sale.storeId())
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
            // TenantContext already set above so findByCurrentTenant() resolves correctly.
            StockAlertChannel channel = preferencesQuery
                    .find(TenantContext.getCurrentTenant())
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            // ── Push notification ────────────────────────────────────────────
            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    notificationPort.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-VALIDATED] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                }
            }

            // ── WhatsApp notification ────────────────────────────────────────
            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppPort.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-VALIDATED] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            // Best-effort — sale validation MUST NOT fail due to notification errors
            log.warn("[SALE-VALIDATED] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Fires when an employee submits a sale containing draft products (PENDING_VALIDATION).
     * Notifies the owner so they can review and validate the pending sale.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalePendingValidation(SalePendingValidationEvent event) {
        log.info("[SALE-PENDING] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

            SaleView sale = saleQueryPort.findById(TenantContext.getCurrentTenant(), event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-PENDING] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            String employeeName = employeeQuery.findByUserId(TenantContext.getCurrentTenant(), sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), sale.storeId())
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
                    .find(TenantContext.getCurrentTenant())
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    notificationPort.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-PENDING] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                }
            }

            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppPort.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-PENDING] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.warn("[SALE-PENDING] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Fires when an employee finalises a sale that contained originally-draft products
     * (new flow — stock entered at checkout, sale recorded as COMPLETED immediately).
     * Notifies the owner: {@code draftsPromoted} (OWNER seller) → products were activated;
     * otherwise (EMPLOYEE seller, v1s-12-10) → drafts were sold and await validation.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaleDraftProductsUpgraded(SaleDraftProductsUpgradedEvent event) {
        log.info("[SALE-DRAFT-UPGRADED] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

            SaleView sale = saleQueryPort.findById(TenantContext.getCurrentTenant(), event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-DRAFT-UPGRADED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            // v1s-12-10: an EMPLOYEE seller no longer promotes drafts (promotion is OWNER-only),
            // so the owner is told the drafts await validation instead of "activated". The
            // seller's role travels on the event (draftsPromoted) — never inferred from the
            // Employee row (an owner may have one; an employee's row may be missing).
            boolean draftsPromoted = event.draftsPromoted();
            String employeeName = employeeQuery.findByUserId(TenantContext.getCurrentTenant(), sale.employeeId())
                    .map(e -> e.firstName() + " " + e.lastName())
                    .orElseGet(() -> userQuery.findPhoneNumber(sale.employeeId())
                            .orElse("Employé inconnu"));

            String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), sale.storeId())
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
                    .find(TenantContext.getCurrentTenant())
                    .map(p -> p.stockAlertChannel())
                    .orElse(StockAlertChannel.PUSH);

            if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
                try {
                    notificationPort.notifyOwners(event.tenantId(), payload);
                } catch (Exception e) {
                    log.warn("[SALE-DRAFT-UPGRADED] Push failed for sale={}: {}", event.saleId(), e.getMessage());
                }
            }

            if (channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH) {
                String ownerPhone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(event.tenantId())
                        .orElse(null);
                if (ownerPhone != null) {
                    try {
                        whatsAppPort.sendReport(ownerPhone, title + "\n" + body);
                    } catch (Exception e) {
                        log.warn("[SALE-DRAFT-UPGRADED] WhatsApp failed for sale={}: {}", event.saleId(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.warn("[SALE-DRAFT-UPGRADED] Unexpected error for sale={}: {}", event.saleId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
