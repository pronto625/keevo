package com.keevo.whatsappcloud.invoice;

import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.TenantWhatsAppBusiness;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleState;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService.Credentials;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.inbox.application.WhatsAppInboxService;
import com.keevo.whatsappcloud.inbox.domain.model.ContactPhone;
import com.keevo.whatsappcloud.module.WhatsAppCloudModule;
import com.keevo.whatsappcloud.template.application.WhatsAppTemplateService;
import com.keevo.whatsappcloud.template.domain.model.SystemTemplates;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Story 21.5 — {@link TenantWhatsAppBusiness}: an invoice / quote sent from the company's own number with the approved
 * template {@code bref_facture} (port of Bref {@code sendInvoiceDocument}): the PDF uploaded to the number as media,
 * then the template with the PDF as its document header and {client, « facture » | « devis », reference, shop} as body;
 * the message is stored in the client's conversation. Usable only when the module is fully enabled (not in its
 * read-only grace), the number connected and the template approved. Cost of the check: cached entitlement + 1 PK read
 * + 1 indexed template read; of the send: the PDF render, 2 Graph calls, 1 insert + 1 upsert.
 */
@Component
public class WhatsAppBusinessDocumentSender implements TenantWhatsAppBusiness {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppBusinessDocumentSender.class);

    static final String PROVIDER = "whatsapp-cloud";

    private final EntitlementService entitlements;
    private final WhatsAppAccountService accounts;
    private final WhatsAppTemplateService templates;
    private final WhatsAppInboxService inbox;
    private final MetaGraphClient graph;
    private final ModuleProvisioner provisioner;

    public WhatsAppBusinessDocumentSender(EntitlementService entitlements, WhatsAppAccountService accounts,
                                          WhatsAppTemplateService templates, WhatsAppInboxService inbox,
                                          MetaGraphClient graph, ModuleProvisioner provisioner) {
        this.entitlements = entitlements;
        this.accounts = accounts;
        this.templates = templates;
        this.inbox = inbox;
        this.graph = graph;
        this.provisioner = provisioner;
    }

    @Override
    public boolean canSendDocuments(String tenantSchema) {
        try {
            if (!graph.isEnabled()
                    || entitlements.state(tenantSchema, WhatsAppCloudModule.ID) != ModuleState.ENABLED) {
                return false;
            }
            // Story 21.6 — the module tables at their latest version (the sent document is stored in the inbox)
            provisioner.ensureProvisioned(tenantSchema, WhatsAppCloudModule.ID);
            return accounts.status(tenantSchema).connected()
                    && templates.invoiceTemplateApproved(tenantSchema);
        } catch (RuntimeException e) {
            // module tables not provisioned yet, database hiccup: the platform path is used
            log.warn("[WhatsApp Cloud] document sending check failed for tenant {} ({})", tenantSchema,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public DeliveryResult sendDocument(String tenantSchema, BusinessDocument document, Supplier<Pdf> pdf) {
        String to = ContactPhone.digitsOrNull(document.to());
        Optional<Credentials> credentials = accounts.credentials(tenantSchema);
        if (to == null || credentials.isEmpty()) {
            return DeliveryResult.notDelivered();
        }
        Credentials c = credentials.get();
        Pdf file = pdf.get();
        List<String> values = List.of(orDash(document.clientName()), document.quote() ? "devis" : "facture",
                orDash(document.reference()), orDash(document.shopName()));
        String mediaId;
        try {
            mediaId = graph.uploadMedia(c.phoneNumberId(), c.token(), file.content(), "application/pdf",
                    file.filename());
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] invoice PDF not uploaded for tenant {} ({})", tenantSchema, e.kind());
            return DeliveryResult.notDelivered(); // nothing reached the client
        }
        List<Map<String, Object>> components = List.of(
                Map.of("type", "header", "parameters", List.of(Map.of("type", "document",
                        "document", Map.of("id", mediaId, "filename", file.filename())))),
                Map.of("type", "body", "parameters",
                        values.stream().map(v -> Map.<String, Object>of("type", "text", "text", v)).toList()));
        String waId;
        try {
            waId = graph.sendTemplate(c.phoneNumberId(), c.token(), to, SystemTemplates.INVOICE_NAME,
                    SystemTemplates.INVOICE_LANGUAGE, components);
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] invoice template not sent for tenant {} ({})", tenantSchema, e.kind());
            return e.kind() == MetaGraphException.Kind.UNKNOWN ? DeliveryResult.uncertain(PROVIDER)
                    : DeliveryResult.notDelivered();
        }
        inbox.recordOutbound(tenantSchema, to, "document", TemplateText.render(SystemTemplates.INVOICE_BODY, values),
                waId, null);
        return DeliveryResult.deliveredBy(PROVIDER);
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? "-" : v.strip();
    }
}
