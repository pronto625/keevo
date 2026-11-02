package com.keevo.messaging.notification.application.listener;

import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.catalog.product.ProductPriceChangedEvent;
import com.keevo.shared.event.catalog.product.SalePriceOverriddenEvent;
import com.keevo.shared.module.ModuleScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PriceChangeNotificationListener — tells the owner when an EMPLOYEE changes a selling price (Story 17.7):
 * <ul>
 *   <li>catalogue edit ({@link ProductPriceChangedEvent}, REST PATCH and sync {@code UPDATE_PRODUCT});</li>
 *   <li>item sold at a price other than the catalogue one ({@link SalePriceOverriddenEvent}, REST and sync
 *       {@code CREATE_SALE}, one event per overridden item).</li>
 * </ul>
 *
 * <p>Owner actors are skipped: catalogue path by the role on the event; till path by looking the user up among the
 * tenant's employees ({@link EmployeeQueryPort#findByUserId}) — not an employee → owner or unknown → skipped.
 * The lookup runs in the handler, where the module-listener interceptor has bound {@code TenantContext} to the
 * event's tenant (the periodic sweep has no tenant).
 *
 * <p>Gating: owner alerts are the paid module {@code alerts} ({@code @ModuleScoped}) — a Free tenant is never
 * notified. Async after commit ({@code @ApplicationModuleListener}): a rolled-back sale or edit never notifies.
 *
 * <p>Batching, same as {@link StockModificationNotificationListener}: both sources share one window per (tenant,
 * employee) of {@code keevo.notification.employee-action-window-min} minutes; the periodic sweep sends one
 * notification per expired window — single message for one change, recap for several. <b>Not durable</b>: the
 * window lives in memory, a restart before the sweep loses it; a send failure is only logged (best effort).
 */
@Component
public class PriceChangeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PriceChangeNotificationListener.class);

    static final String TYPE_SINGLE = "EMPLOYEE_PRICE_CHANGED";
    static final String TYPE_BATCH = "EMPLOYEE_PRICE_CHANGED_BATCH";
    static final String SOURCE_CATALOGUE = "CATALOGUE";
    static final String SOURCE_SALE = "SALE";
    private static final String FALLBACK_NAME = "Un employé";
    private static final long DEFAULT_WINDOW_MIN = 5;

    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final EmployeeQueryPort employeeQuery;
    private final long windowMin;
    private final Clock clock;

    private final ConcurrentHashMap<String, BatchWindow> batchWindows = new ConcurrentHashMap<>();

    @Autowired
    public PriceChangeNotificationListener(
            PushNotifier pushNotifier,
            WhatsAppSender whatsAppSender,
            OwnerRecipientResolver ownerRecipientResolver,
            EmployeeQueryPort employeeQuery,
            @Value("${keevo.notification.employee-action-window-min:" + DEFAULT_WINDOW_MIN + "}")
            long windowMin) {
        this(pushNotifier, whatsAppSender, ownerRecipientResolver, employeeQuery, windowMin, Clock.systemUTC());
    }

    /** Test-only constructor — injects a controllable {@link Clock}. */
    PriceChangeNotificationListener(
            PushNotifier pushNotifier,
            WhatsAppSender whatsAppSender,
            OwnerRecipientResolver ownerRecipientResolver,
            EmployeeQueryPort employeeQuery,
            long windowMin,
            Clock clock) {
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.employeeQuery = employeeQuery;
        this.windowMin = windowMin;
        this.clock = clock;
    }

    /** Catalogue price edited (REST / sync). */
    @ModuleScoped("alerts")
    @ApplicationModuleListener
    public void onProductPriceChanged(ProductPriceChangedEvent event) {
        if (!"EMPLOYEE".equals(event.actorRole())) {
            log.debug("[PRICE-NOTIF] Skipping catalogue change — actor is not EMPLOYEE (role={})", event.actorRole());
            return;
        }
        if (event.oldPrice() == event.newPrice()) {
            return; // defensive: the producer only publishes real changes
        }
        // a lookup failure propagates: the publication stays incomplete and is resubmitted
        String name = findEmployee(event.tenantId(), event.actorId()).map(PriceChangeNotificationListener::fullName)
                .orElse(FALLBACK_NAME);
        accumulate(new PriceChange(SOURCE_CATALOGUE, event.productId(), null, event.productName(), event.oldPrice(),
                event.newPrice(), event.actorId(), name, event.tenantId()));
    }

    /** Item sold at a price other than the catalogue one (REST / sync sale). */
    @ModuleScoped("alerts")
    @ApplicationModuleListener
    public void onSalePriceOverridden(SalePriceOverriddenEvent event) {
        if (event.cataloguePrice() == event.appliedPrice()) {
            return;
        }
        // a lookup failure propagates: the publication stays incomplete and is resubmitted
        Optional<EmployeeView> employee = findEmployee(event.tenantId(), event.actorId());
        if (employee.isEmpty()) {
            log.debug("[PRICE-NOTIF] Skipping till override — seller is not an employee of tenant={}",
                    event.tenantId());
            return;
        }
        accumulate(new PriceChange(SOURCE_SALE, event.productId(), event.saleId(), event.productName(),
                event.cataloguePrice(), event.appliedPrice(), event.actorId(), fullName(employee.get()),
                event.tenantId()));
    }

    /** Periodic sweep: one notification per expired window (single for 1 change, recap for N ≥ 2). */
    @Scheduled(fixedDelay = 30_000)
    public void flushExpiredWindows() {
        Instant now = Instant.now(clock);
        Duration window = Duration.ofMinutes(windowMin);
        for (String key : batchWindows.keySet()) {
            List<PriceChange> toSend = new ArrayList<>();
            batchWindows.computeIfPresent(key, (k, w) -> {
                if (w.windowStart == null || now.isAfter(w.windowStart.plus(window))) {
                    toSend.addAll(w.changes);
                    return null;
                }
                return w;
            });
            if (!toSend.isEmpty()) {
                try {
                    if (toSend.size() == 1) {
                        dispatchSingle(toSend.get(0));
                    } else {
                        dispatchBatch(toSend);
                    }
                } catch (Exception e) {
                    log.warn("[PRICE-NOTIF] Failed to flush window", e);
                }
            }
        }
    }

    /** Test hook — number of open batch windows. */
    int openWindowCount() {
        return batchWindows.size();
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private Optional<EmployeeView> findEmployee(String tenantId, UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return employeeQuery.findByUserId(tenantId, userId);
    }

    private void accumulate(PriceChange change) {
        String key = change.tenantId() + ":" + change.actorId();
        batchWindows.compute(key, (k, window) -> {
            BatchWindow w = window != null ? window : new BatchWindow();
            if (w.windowStart == null) {
                w.windowStart = Instant.now(clock);
            }
            w.changes.add(change);
            return w;
        });
    }

    private void dispatchSingle(PriceChange c) {
        String product = productLabel(c);
        String title;
        String body;
        if (SOURCE_CATALOGUE.equals(c.source())) {
            title = "💰 Prix modifié par " + c.actorName();
            body = c.actorName() + " a changé le prix de « " + product + " » : "
                    + groupDigits(c.oldPrice()) + " → " + formatXaf(c.newPrice()) + ".";
        } else {
            title = "💰 Prix modifié à la caisse";
            body = c.actorName() + " a vendu « " + product + " » à " + formatXaf(c.newPrice())
                    + " au lieu de " + formatXaf(c.oldPrice()) + ".";
        }
        NotificationPayload payload = NotificationPayload.of(
                TYPE_SINGLE, title, body,
                "/products/" + c.productId() + "/edit", // existing app route (product form)
                singleData(c, product));
        send(c.tenantId(), payload, body);
    }

    private static Map<String, String> singleData(PriceChange c, String product) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("productId", String.valueOf(c.productId()));
        data.put("productName", product);
        data.put("oldPrice", String.valueOf(c.oldPrice()));
        data.put("newPrice", String.valueOf(c.newPrice()));
        data.put("source", c.source());
        data.put("actorId", String.valueOf(c.actorId()));
        data.put("tenantId", c.tenantId());
        if (c.saleId() != null) {
            data.put("saleId", c.saleId().toString()); // till path only
        }
        return data;
    }

    private void dispatchBatch(List<PriceChange> changes) {
        PriceChange first = changes.get(0);
        long catalogue = changes.stream().filter(c -> SOURCE_CATALOGUE.equals(c.source())).count();
        long sale = changes.size() - catalogue;
        String body = first.actorName() + " a modifié " + changes.size() + " prix (" + catalogue
                + " au catalogue, " + sale + " à la caisse).";
        NotificationPayload payload = NotificationPayload.of(
                TYPE_BATCH,
                "💰 Prix modifiés par " + first.actorName(),
                body,
                "/stock/overview", // same existing app route as the stock-modification recap
                Map.of("count", String.valueOf(changes.size()),
                       "catalogueCount", String.valueOf(catalogue),
                       "saleCount", String.valueOf(sale),
                       "actorId", String.valueOf(first.actorId()),
                       "tenantId", first.tenantId()));
        send(first.tenantId(), payload, body);
    }

    private void send(String tenantId, NotificationPayload payload, String whatsAppBody) {
        try {
            pushNotifier.notifyOwners(tenantId, payload);
        } catch (Exception e) {
            log.warn("[PRICE-NOTIF] Push failed for tenant={}", tenantId, e);
        }
        String phone;
        try {
            phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId).orElse(null);
        } catch (Exception e) {
            log.warn("[PRICE-NOTIF] Owner phone lookup failed for tenant={}", tenantId, e);
            return;
        }
        if (phone == null) {
            log.debug("[PRICE-NOTIF] No owner phone for tenant={}, skipping WhatsApp", tenantId);
            return;
        }
        try {
            whatsAppSender.sendReport(phone, whatsAppBody);
        } catch (Exception e) {
            log.warn("[PRICE-NOTIF] WhatsApp failed for tenant={}", tenantId, e);
        }
    }

    private static String fullName(EmployeeView e) {
        String first = e.firstName() == null ? "" : e.firstName().trim();
        String last = e.lastName() == null ? "" : e.lastName().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? FALLBACK_NAME : name;
    }

    private static String productLabel(PriceChange c) {
        return c.productName() != null && !c.productName().isBlank() ? c.productName() : "un produit";
    }

    /** « 1 500 FCFA » — same grouping as the other owner messages. */
    static String formatXaf(int amount) {
        return groupDigits(amount) + " FCFA";
    }

    private static String groupDigits(int amount) {
        String str = String.valueOf(Math.abs(amount));
        StringBuilder buf = new StringBuilder(amount < 0 ? "-" : "");
        int len = str.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) buf.append(' ');
            buf.append(str.charAt(i));
        }
        return buf.toString();
    }

    // ── Batch window holder ────────────────────────────────────────────

    private record PriceChange(String source, UUID productId, UUID saleId, String productName, int oldPrice, int newPrice,
                               UUID actorId, String actorName, String tenantId) {}

    private static class BatchWindow {
        Instant windowStart;
        final List<PriceChange> changes = new ArrayList<>();
    }
}
