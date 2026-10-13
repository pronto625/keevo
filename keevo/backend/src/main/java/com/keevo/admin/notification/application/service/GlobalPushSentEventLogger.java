package com.keevo.admin.notification.application.service;

import com.keevo.admin.notification.domain.model.GlobalPushSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs every global push sent by the super admin (Story 9-5 — admin action trace with actorId). */
@Component
public class GlobalPushSentEventLogger {

    private static final Logger log = LoggerFactory.getLogger(GlobalPushSentEventLogger.class);

    @EventListener
    public void on(GlobalPushSentEvent event) {
        log.info("[ADMIN AUDIT] global push sent by {} — « {} », target {}, {} device(s), at {}",
                event.actorId(), event.title(), event.target(), event.recipientCount(), event.sentAt());
    }
}
