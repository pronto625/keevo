package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.event.catalog.sale.DayClosedEvent;
import com.keevo.shared.event.catalog.sale.DayClosureSummary;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
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
 * <p>Story 12.6a: durable module listener (async, <b>after commit</b>, registry publication; tenant bound from
 * the event) — no push for a closure whose transaction rolled back. {@code @DeliverOnce}: a registry replay
 * never sends the push twice. A send failure stays logged (best-effort, unchanged).
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

    private final PushNotifier pushNotifier;
    private final StoreQueryPort storeQuery;

    public DayClosureNotificationListener(PushNotifier pushNotifier,
                                          StoreQueryPort storeQuery) {
        this.pushNotifier = pushNotifier;
        this.storeQuery = storeQuery;
    }

    @ApplicationModuleListener
    @DeliverOnce
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

            pushNotifier.notifyOwners(event.tenantId(), NotificationPayload.of(
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

    /** Store lookup in the closure's tenant schema (bound by the module-listener interceptor, 12.4). */
    private Optional<StoreView> findStore(DayClosedEvent event) {
        try {
            return storeQuery.findById(event.tenantId(), event.storeId());
        } catch (Exception e) {
            log.warn("[FCM-CLOSURE] Store lookup failed for storeId={}: {}", event.storeId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** French grouping (« 4 400 », not the default-locale « 4,400 » shown on device). */
    static String amount(long value) {
        return String.format(Locale.FRANCE, "%,d", value).replace('\u202F', ' ').replace('\u00A0', ' ');
    }
}
