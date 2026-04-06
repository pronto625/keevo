package com.keevo.messaging.notification.application.listener;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DayClosureNotificationListener — dispatches FCM push notification to owner(s)
 * when a day is closed (manual or automatic).
 *
 * <p>Async best-effort: closure must NOT fail due to a notification error.
 *
 * <p>Story 8.0 — FCM Push Notifications.
 */
@Component
public class DayClosureNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DayClosureNotificationListener.class);

    private final NotificationPort notificationPort;

    public DayClosureNotificationListener(NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    @Async
    @EventListener
    public void onDayClosed(DayClosedEvent event) {
        log.info("[FCM-CLOSURE] DayClosedEvent received: closureId={} storeId={} tenantId={} isAutomatic={}",
                event.closureId(), event.storeId(), event.tenantId(), event.isAutomatic());

        try {
            DayClosureSummary s = event.summary();
            String mode = event.isAutomatic() ? "automatique" : "manuelle";
            String body = String.format(
                    "Clôture %s — %d vente(s) · %,d FCFA · Espèces: %,d · Mobile Money: %,d",
                    mode, s.totalSales(), s.totalRevenue(), s.cashAmount(), s.momoAmount());

            notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                    "DAY_CLOSED",
                    "📊 Clôture journalière",
                    body,
                    "/reports/history",
                    Map.of(
                            "closureId",    event.closureId().toString(),
                            "storeId",      event.storeId().toString(),
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
}
