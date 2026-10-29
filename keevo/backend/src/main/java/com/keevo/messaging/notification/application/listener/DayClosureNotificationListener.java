package com.keevo.messaging.notification.application.listener;

import com.keevo.commerce.sale.domain.event.DayClosedEvent;
import com.keevo.commerce.api.DayClosureSummary;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DayClosureNotificationListener — dispatches FCM push notification to owner(s)
 * when a day is closed (manual or automatic).
 *
 * <p>Async best-effort: closure must NOT fail due to a notification error.
 *
 * <p>Story 8.0 — FCM Push Notifications.
 *
 * <p>J21 (device test): the body names the store and uses XAF (A14). No push for a
 * warehouse closure, nor for an automatic closure without any sale (the closure is
 * still recorded for the reporting chain — FR51 — only the push is skipped).
 */
@Component
public class DayClosureNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DayClosureNotificationListener.class);

    private final NotificationPort notificationPort;
    private final StoreQueryPort storeQuery;

    public DayClosureNotificationListener(NotificationPort notificationPort,
                                          StoreQueryPort storeQuery) {
        this.notificationPort = notificationPort;
        this.storeQuery = storeQuery;
    }

    @Async
    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("[FCM-CLOSURE] DayClosedEvent received: closureId={} storeId={} tenantId={} isAutomatic={}",
                event.closureId(), event.storeId(), event.tenantId(), event.isAutomatic());

        try {
            DayClosureSummary s = event.summary();
            if (event.isAutomatic() && s.totalSales() == 0) {
                log.info("[FCM-CLOSURE] Automatic closure without sales — no push (closureId={})",
                        event.closureId());
                return;
            }
            Optional<StoreView> store = findStore(event);
            if (store.map(st -> st.type() == StoreType.WAREHOUSE).orElse(false)) {
                log.info("[FCM-CLOSURE] Warehouse closure — no push (closureId={})", event.closureId());
                return;
            }
            String storeName = store.map(StoreView::name).orElse("Boutique");
            String mode = event.isAutomatic() ? "automatique" : "manuelle";
            String body = String.format(
                    "%s — Clôture %s : %d vente(s) · %s XAF · Espèces : %s · Mobile Money : %s",
                    storeName, mode, s.totalSales(), amount(s.totalRevenue()),
                    amount(s.cashAmount()), amount(s.momoAmount()));

            notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                    "DAY_CLOSED",
                    "📊 Clôture journalière",
                    body,
                    "/reports/history",
                    Map.of(
                            "closureId",    event.closureId().toString(),
                            "storeId",      event.storeId().toString(),
                            "storeName",    storeName,
                            "tenantId",     event.tenantId(),
                            "totalSales",   String.valueOf(s.totalSales()),
                            "totalRevenue", String.valueOf(s.totalRevenue()),
                            "isAutomatic",  String.valueOf(event.isAutomatic())
                    )
            ));
        } catch (Exception e) {
            log.warn("[FCM-CLOSURE] Failed to notify owners for closureId={}: {}",
                    event.closureId(), e.getMessage());
        }
    }

    /** Store lookup in the closure's tenant schema (async thread: no tenant context yet). */
    private Optional<StoreView> findStore(DayClosedEvent event) {
        String previous = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(event.tenantId());
            return storeQuery.findById(TenantContext.getCurrentTenant(), event.storeId());
        } catch (Exception e) {
            log.warn("[FCM-CLOSURE] Store lookup failed for storeId={}: {}", event.storeId(), e.getMessage());
            return Optional.empty();
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** French grouping (« 4 400 », not the default-locale « 4,400 » shown on device). */
    static String amount(long value) {
        return String.format(Locale.FRANCE, "%,d", value).replace('\u202F', ' ').replace('\u00A0', ' ');
    }
}
