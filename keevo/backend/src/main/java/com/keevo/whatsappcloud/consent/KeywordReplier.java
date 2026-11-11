package com.keevo.whatsappcloud.consent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService.Credentials;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 21.6 — sends the STOP / START confirmation back to the contact, from the number he wrote to (company or
 * platform), after the change is committed, on a small bounded pool (Meta's webhook answers at once). At most one
 * confirmation per (number, contact) per minute (Bref {@code KEYWORD_REPLY_COOLDOWN_MS}): a third party or a STOP /
 * START loop cannot trigger a burst. Free text is allowed: the contact has just written (24-hour window). Best effort:
 * a failure is logged, the consent change stands.
 */
@Component
public class KeywordReplier {

    private static final Logger log = LoggerFactory.getLogger(KeywordReplier.class);
    static final Duration COOLDOWN = Duration.ofMinutes(1);

    /** One confirmation to send: inbox key (tenant schema or the platform key), contact digits, answer. */
    public record Reply(String inbox, String contactPhone, KeywordConsent.Answer answer) {
    }

    private final WhatsAppInboxService inbox;
    private final MetaGraphClient graph;
    private final Cache<String, Boolean> recent = Caffeine.newBuilder().expireAfterWrite(COOLDOWN)
            .maximumSize(50_000).build();
    private final ThreadPoolExecutor workers;

    public KeywordReplier(WhatsAppInboxService inbox, MetaGraphClient graph) {
        this.inbox = inbox;
        this.graph = graph;
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500), r -> {
            Thread t = new Thread(r, "whatsapp-keyword-reply-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, (r, executor) -> log.warn("[WhatsApp Cloud] keyword reply queue full: a confirmation was dropped"));
        this.workers.allowCoreThreadTimeOut(true);
    }

    /** Hands the confirmations to the pool; those within the cooldown of their (number, contact) are skipped. */
    public void dispatch(List<Reply> replies) {
        for (Reply r : replies) {
            String key = r.inbox() + ":" + r.contactPhone();
            if (recent.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
                continue; // already confirmed in the last minute
            }
            workers.execute(() -> send(r));
        }
    }

    void send(Reply r) {
        boolean platform = WhatsAppInboxRepository.PLATFORM.equals(r.inbox());
        String text = KeywordConsent.confirmation(r.answer(), platform);
        try {
            Credentials c = inbox.credentials(r.inbox());
            String waId = graph.sendText(c.phoneNumberId(), c.token(), r.contactPhone(), text);
            inbox.recordOutbound(r.inbox(), r.contactPhone(), "text", text, waId, null);
        } catch (MetaGraphException | com.keevo.shared.domain.exception.DomainException e) {
            log.warn("[WhatsApp Cloud] keyword confirmation not sent for {} ({})", r.inbox(),
                    e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.warn("[WhatsApp Cloud] keyword confirmation failed for {} ({})", r.inbox(),
                    e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
