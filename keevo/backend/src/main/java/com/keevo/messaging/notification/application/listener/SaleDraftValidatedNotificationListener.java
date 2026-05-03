package com.keevo.messaging.notification.application.listener;

import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleDraftProductsUpgradedEvent;
import com.keevo.commerce.sale.domain.model.SaleManuallyValidatedEvent;
import com.keevo.commerce.sale.domain.model.SalePendingValidationEvent;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreRepository;
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

    private final SaleRepository saleRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final NotificationPort notificationPort;
    private final WhatsAppPort whatsAppPort;

    public SaleDraftValidatedNotificationListener(
            SaleRepository saleRepository,
            EmployeeRepository employeeRepository,
            StoreRepository storeRepository,
            UserRepository userRepository,
            TenantPreferencesRepository tenantPreferencesRepository,
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort) {
        this.saleRepository = saleRepository;
        this.employeeRepository = employeeRepository;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
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

            Sale sale = saleRepository.findById(event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-VALIDATED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            // Resolve employee display name
            String employeeName = employeeRepository.findByUserId(sale.getEmployeeId())
                    .map(e -> e.getFirstName() + " " + e.getLastName())
                    .orElseGet(() -> userRepository.findById(sale.getEmployeeId())
                            .map(u -> u.getPhoneNumber())
                            .orElse("Employé inconnu"));

            // Resolve store name
            String storeName = storeRepository.findById(sale.getStoreId())
                    .map(Store::name)
                    .orElse("Boutique");

            // Collect product names from sale items
            String productNames = sale.getItems().stream()
                    .map(item -> item.getProductName())
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
                            "storeId", sale.getStoreId().toString())
            );

            // Resolve channel preference (falls back to PUSH if prefs not found)
            // TenantContext already set above so findByCurrentTenant() resolves correctly.
            StockAlertChannel channel = tenantPreferencesRepository
                    .findByCurrentTenant()
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
                String ownerPhone = userRepository.findOwnerByTenantSchemaName(event.tenantId())
                        .map(u -> u.getPhoneNumber())
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

            Sale sale = saleRepository.findById(event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-PENDING] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            String employeeName = employeeRepository.findByUserId(sale.getEmployeeId())
                    .map(e -> e.getFirstName() + " " + e.getLastName())
                    .orElseGet(() -> userRepository.findById(sale.getEmployeeId())
                            .map(u -> u.getPhoneNumber())
                            .orElse("Employé inconnu"));

            String storeName = storeRepository.findById(sale.getStoreId())
                    .map(Store::name)
                    .orElse("Boutique");

            String productNames = sale.getItems().stream()
                    .map(item -> item.getProductName())
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

            StockAlertChannel channel = tenantPreferencesRepository
                    .findByCurrentTenant()
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
                String ownerPhone = userRepository.findOwnerByTenantSchemaName(event.tenantId())
                        .map(u -> u.getPhoneNumber())
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
     * Notifies the owner so they know new products were activated.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaleDraftProductsUpgraded(SaleDraftProductsUpgradedEvent event) {
        log.info("[SALE-DRAFT-UPGRADED] sale={} actor={} tenant={}",
                event.saleId(), event.actorId(), event.tenantId());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

            Sale sale = saleRepository.findById(event.saleId()).orElse(null);
            if (sale == null) {
                log.warn("[SALE-DRAFT-UPGRADED] Sale {} not found — skipping notification", event.saleId());
                return;
            }

            String employeeName = employeeRepository.findByUserId(sale.getEmployeeId())
                    .map(e -> e.getFirstName() + " " + e.getLastName())
                    .orElseGet(() -> userRepository.findById(sale.getEmployeeId())
                            .map(u -> u.getPhoneNumber())
                            .orElse("Employé inconnu"));

            String storeName = storeRepository.findById(sale.getStoreId())
                    .map(Store::name)
                    .orElse("Boutique");

            String productNames = sale.getItems().stream()
                    .filter(item -> event.originalDraftProductIds().contains(item.getProductId()))
                    .map(item -> item.getProductName())
                    .collect(Collectors.joining(", "));

            String title = "🆕 Nouveaux produits activés";
            String body = employeeName + " — " + storeName + " : " + productNames
                    + " (produits brouillons activés lors d'une vente)";
            String deepLink = "/pos/sales-history/" + event.saleId();

            NotificationPayload payload = NotificationPayload.of(
                    "SALE_DRAFT_PRODUCTS_UPGRADED",
                    title,
                    body,
                    deepLink,
                    Map.of("saleId", event.saleId().toString(),
                            "storeId", event.storeId().toString())
            );

            StockAlertChannel channel = tenantPreferencesRepository
                    .findByCurrentTenant()
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
                String ownerPhone = userRepository.findOwnerByTenantSchemaName(event.tenantId())
                        .map(u -> u.getPhoneNumber())
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
