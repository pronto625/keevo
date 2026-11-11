package com.keevo.whatsappcloud.platform.application;

import com.keevo.shared.capability.notification.PlatformWhatsApp;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.whatsappcloud.config.MetaErrors;
import com.keevo.whatsappcloud.config.MetaSends;
import com.keevo.whatsappcloud.config.PlatformNumberSettings;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.ContactPhone;
import com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.platform.domain.port.out.PlatformOptOutRepository;
import com.keevo.whatsappcloud.template.domain.model.TemplateComponents;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.6 — the platform WhatsApp number (Bref « Bref Point ») for the super-admin: implements the shared
 * {@link PlatformWhatsApp} port of the platform campaigns (template sends recorded in the platform inbox with their
 * campaign, so Meta's statuses come back; STOP list), and lists the number's templates. The number's id, WABA and
 * token come from {@link PlatformNumberSettings}; nothing is sent when it is not configured or the Graph API is off.
 */
@Service
public class PlatformWhatsAppService implements PlatformWhatsApp {

    private static final Logger log = LoggerFactory.getLogger(PlatformWhatsAppService.class);

    private final PlatformNumberSettings platform;
    private final MetaGraphClient graph;
    private final WhatsAppInboxRepository inbox;
    private final PlatformOptOutRepository optOuts;
    private final Clock clock;

    public PlatformWhatsAppService(PlatformNumberSettings platform, MetaGraphClient graph,
                                   WhatsAppInboxRepository inbox, PlatformOptOutRepository optOuts, Clock clock) {
        this.platform = platform;
        this.graph = graph;
        this.inbox = inbox;
        this.optOuts = optOuts;
        this.clock = clock;
    }

    @Override
    public boolean isConfigured() {
        return platform.isConfigured() && graph.isEnabled();
    }

    @Override
    public SendResult sendTemplate(String to, TemplateMessage message, UUID campaignId) {
        if (!isConfigured()) {
            return SendResult.of(SendResult.Outcome.UNAVAILABLE, "Numéro WhatsApp de la plateforme non configuré");
        }
        String phone = ContactPhone.digitsOrNull(to);
        if (phone == null) {
            return SendResult.of(SendResult.Outcome.FAILED, "Numéro invalide");
        }
        SendResult result = MetaSends.send(() -> graph.sendTemplate(platform.phoneNumberId(), platform.token(), phone,
                message.name(), message.language(), TemplateComponents.build(message.bodyText(), message.values(),
                        message.headerFormat(), message.header())));
        if (result.outcome() == SendResult.Outcome.SENT) {
            record(phone, TemplateText.render(message.bodyText(), message.values()), result.waMessageId(), campaignId);
        }
        return result;
    }

    @Override
    public Set<String> optedOut(Collection<String> phones) {
        return optOuts.optedOut(phones);
    }

    /**
     * The approved-or-not templates of the platform WABA as Meta lists them (name, language, status, category,
     * header format, body), APPROVED first then by name.
     *
     * @throws DomainException {@code PLATFORM_WHATSAPP_UNAVAILABLE} 503 when the number or its WABA is not configured,
     *                         {@code WHATSAPP_CLOUD_META_ERROR} 502
     */
    public List<MetaTemplate> templates() {
        if (!isConfigured() || platform.wabaId().isEmpty()) {
            throw new DomainException(ErrorCode.PLATFORM_WHATSAPP_UNAVAILABLE);
        }
        try {
            return graph.listTemplates(platform.wabaId(), platform.token()).stream()
                    .sorted(Comparator.comparing((MetaTemplate t) -> !"APPROVED".equals(t.status()))
                            .thenComparing(MetaTemplate::name, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (MetaGraphException e) {
            throw MetaErrors.of(e, false);
        }
    }

    /** The sent template in the platform inbox (best effort: the send stands). */
    private void record(String phone, String body, String waId, UUID campaignId) {
        try {
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            String stored = body == null ? null : body.length() > 8192 ? body.substring(0, 8192) : body;
            inbox.insertAll(WhatsAppInboxRepository.PLATFORM, List.of(new ChatMessage(UUID.randomUUID(), phone,
                    ChatMessage.Direction.OUT, "template", stored, waId, "SENT", null, null, now, campaignId)));
            inbox.upsertConversations(WhatsAppInboxRepository.PLATFORM, List.of(new ConversationDelta(phone, null,
                    WhatsAppInboxService.preview(stored), "template", ChatMessage.Direction.OUT, now, 0, null)));
        } catch (RuntimeException e) {
            log.warn("[WhatsApp Cloud] platform message not stored ({})", e.getClass().getSimpleName());
        }
    }
}
