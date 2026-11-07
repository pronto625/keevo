package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.catalog.api.ProductLookupPort;
import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.api.DocumentClientInput;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.6 — derivation of the CRM client and the catalogue products of an invoice when it is issued (Bref: issuing
 * a sale records its customer and its products without any prior data entry, FUS-FR16).
 *
 * <p>Pattern: application service orchestrating optional capabilities (hexagonal ports + capability registry, the
 * 13.x module-autonomy pattern). Invoicing owns the « when » — the single choke point is the counted issue of
 * {@link InvoiceLifecycleUseCase} (REST issue, sync {@code ISSUE_INVOICE}, quote conversion), never the POS bridge,
 * quotes, drafts, credit notes nor imports — and the rebuild of the draft; {@code crm} ({@link ClientLookupPort}) and
 * {@code catalog} ({@link ProductLookupPort}) own the « how », each half skipped on its own when its module is not
 * fully enabled for the tenant (absent, disabled or read-only).
 *
 * <p>Gate: the tenant preference {@link DeriveFromDocumentsPreference} (explicit choice, else on for a DOCUMENT
 * tenant). Client: only a draft client without CRM link and with a phone. Products: only lines without product.
 * The draft is rebuilt ({@link Invoice#withDraftContent}: client link and line product ids, recomputed with the
 * settings of the issue, so the issued totals are exactly those of an issue without derivation) and saved, in the
 * issue's transaction, before the issue checks. Nothing to link → no query at all beyond the gates.
 *
 * <p>Never fails the invoice: the capabilities answer empty instead of throwing for a business reason.
 */
@Service
public class DocumentDerivationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDerivationService.class);

    static final String CRM = "crm";
    static final String CATALOG = "catalog";

    private final CapabilityRegistry capabilities;
    private final EntitlementService entitlements;
    private final DeriveFromDocumentsPreference preference;
    private final InvoiceRepository invoices;

    public DocumentDerivationService(CapabilityRegistry capabilities, EntitlementService entitlements,
                                     DeriveFromDocumentsPreference preference, InvoiceRepository invoices) {
        this.capabilities = capabilities;
        this.entitlements = entitlements;
        this.preference = preference;
        this.invoices = invoices;
    }

    /**
     * The draft to issue: {@code draft} itself when nothing is derived, else the rebuilt draft (already saved).
     *
     * @param stored the tenant's invoicing settings as stored (their derivation preference)
     * @param seller the settings the invoice is issued with (the rebuilt draft is computed with them)
     */
    public Invoice derive(Invoice draft, InvoicingSettings stored, InvoicingSettings seller, UUID actorId,
                          String tenantSchema, Instant now) {
        if (draft.kind() != InvoiceKind.INVOICE || draft.status() != InvoiceStatus.DRAFT
                || draft.sourceSaleId() != null) {
            return draft;
        }
        ClientInfo client = draft.client();
        boolean clientWanted = client != null && client.clientId() == null && client.phone() != null;
        List<ProductLookupPort.DocumentLine> lineInputs = draft.lines().stream()
                .filter(l -> l.productId() == null && ProductLookupPort.productName(l.description()) != null)
                .map(l -> new ProductLookupPort.DocumentLine(l.description(), l.reference(), l.unitPrice()))
                .toList();
        if (!clientWanted && lineInputs.isEmpty()) {
            return draft;
        }
        if (!preference.resolve(stored, tenantSchema)) {
            return draft;
        }

        UUID clientId = null;
        if (clientWanted) {
            clientId = writable(ClientLookupPort.class, CRM, tenantSchema)
                    .flatMap(crm -> crm.findOrCreateForDocument(tenantSchema, new DocumentClientInput(client.name(),
                            client.phone(), client.email(), client.rccm(), client.niu(), client.address()), actorId))
                    .orElse(null);
        }
        Map<String, UUID> productIds = lineInputs.isEmpty() ? Map.of()
                : writable(ProductLookupPort.class, CATALOG, tenantSchema)
                        .map(catalog -> catalog.findOrCreateForDocument(tenantSchema, lineInputs, actorId))
                        .orElse(Map.of());

        boolean linkedLine = false;
        List<LineInput> lines = new ArrayList<>(draft.lines().size());
        for (InvoiceLine line : draft.lines()) {
            UUID productId = line.productId();
            if (productId == null) {
                String key = ProductLookupPort.keyOf(line.description(), line.reference());
                productId = key == null ? null : productIds.get(key);
                linkedLine |= productId != null;
            }
            lines.add(new LineInput(line.description(), line.quantity(), line.unitPrice(), line.treatment(),
                    productId, line.reference()));
        }
        if (clientId == null && !linkedLine) {
            return draft;
        }
        ClientInfo linkedClient = clientId == null ? client : client.resolved(clientId, client.name(), client.phone());
        Invoice rebuilt = ManageDraftInvoiceUseCase.valid(() -> draft.withDraftContent(linkedClient, lines,
                draft.terms(), seller, now));
        invoices.updateDraft(rebuilt);
        long linkedLines = lines.stream().filter(l -> l.productId() != null).count();
        log.debug("[invoicing] invoice {} derived: client {}, {} line(s) with a product", draft.id(),
                clientId != null ? "linked" : "unchanged", linkedLines);
        return rebuilt;
    }

    /**
     * The capability of {@code moduleId} when that module is fully enabled for the tenant (a read-only grace never
     * creates anything).
     */
    private <T extends com.keevo.shared.module.Capability> Optional<T> writable(Class<T> type, String moduleId,
                                                                               String tenantSchema) {
        if (entitlements.state(tenantSchema, moduleId) != ModuleState.ENABLED) {
            return Optional.empty();
        }
        return capabilities.find(type, tenantSchema);
    }
}
