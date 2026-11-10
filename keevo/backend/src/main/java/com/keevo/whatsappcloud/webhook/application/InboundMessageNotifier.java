package com.keevo.whatsappcloud.webhook.application;

import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 21.5 — tells the company's owners that clients wrote on WhatsApp (Bref {@code WhatsAppInboundMessageEvent} →
 * push): one notification per contact and per webhook delivery (inbox row + push through {@link PushNotifier}), sent
 * after the messages are committed, on a small bounded pool so Meta's webhook answers at once. A full queue drops the
 * notice (logged): the messages are stored and the unread badge shows them. French text, the contact's name (or
 * number) and the beginning of the message; no identifier.
 */
@Component
public class InboundMessageNotifier {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageNotifier.class);

    public static final String TYPE = "WHATSAPP_MESSAGE_RECEIVED";
    static final int PREVIEW = 120;

    /** One contact's new messages in one delivery. */
    public record Notice(String tenantSchema, String contactPhone, String contactName, int count, String preview) {
    }

    private final PushNotifier push;
    private final ThreadPoolExecutor workers;

    public InboundMessageNotifier(PushNotifier push) {
        this.push = push;
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500), r -> {
            Thread t = new Thread(r, "whatsapp-inbound-notify-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, (r, executor) -> log.warn("[WhatsApp Cloud] notification queue full: a new-message push was dropped"));
        this.workers.allowCoreThreadTimeOut(true);
    }

    /** Hands the notices to the pool (never blocks the webhook). */
    public void dispatch(List<Notice> notices) {
        for (Notice notice : notices) {
            workers.execute(() -> send(notice));
        }
    }

    void send(Notice n) {
        try {
            push.notifyOwners(n.tenantSchema(), payload(n));
        } catch (RuntimeException e) {
            log.warn("[WhatsApp Cloud] new-message push failed for tenant {} ({})", n.tenantSchema(),
                    e.getClass().getSimpleName());
        }
    }

    static NotificationPayload payload(Notice n) {
        String who = n.contactName() != null && !n.contactName().isBlank() ? n.contactName().strip()
                : "+" + n.contactPhone();
        String title = n.count() > 1 ? n.count() + " nouveaux messages WhatsApp" : "Nouveau message WhatsApp";
        String text = n.preview() == null ? "" : n.preview();
        if (text.length() > PREVIEW) {
            text = text.substring(0, PREVIEW - 1) + "…";
        }
        return NotificationPayload.of(TYPE, title, who + " : " + text, "/whatsapp/conversations/" + n.contactPhone(),
                Map.of("contactPhone", n.contactPhone()));
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
