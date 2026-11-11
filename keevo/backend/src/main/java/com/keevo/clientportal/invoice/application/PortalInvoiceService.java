package com.keevo.clientportal.invoice.application;

import com.keevo.clientportal.invoice.domain.model.PortalCursor;
import com.keevo.clientportal.invoice.domain.port.out.PortalIndexReader;
import com.keevo.shared.capability.portal.PortalDocuments;
import com.keevo.shared.capability.portal.PortalDocuments.PortalDocument;
import com.keevo.shared.module.ModuleProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — the invoices of the signed-in client across merchants, newest first (FUS-FR29):
 * <ol>
 *   <li>one keyset page of {@code public.invoice_phone_index} for the client's phone, already restricted in SQL to
 *       ACTIVE tenants whose portal is usable (owner switch and module row, {@link PortalIndexReader}) — no
 *       per-merchant entitlement lookup;</li>
 *   <li>for each distinct merchant of the page: the invoicing tables at their latest version
 *       ({@link ModuleProvisioner#ensureProvisioned}, an in-memory no-op once warm) — a merchant whose tables cannot
 *       be brought up to date is left out of this page (logged);</li>
 *   <li>every document read in its own tenant schema in one statement ({@link PortalDocuments}), the minimum of
 *       fields and a 24-hour signed link to the PDF; a document no longer listable (draft, deleted) is skipped.</li>
 * </ol>
 * The cursor is the last index row of the page, so filtered rows never break the paging. Read-only, no transaction.
 */
@Service
public class PortalInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(PortalInvoiceService.class);

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** The module whose tables hold the documents (no dependency on it: the id only). */
    static final String INVOICING = "invoicing";

    /** One page: the documents in index order and the token of the next page (null on the last one). */
    public record Page(List<PortalDocument> items, String nextCursor) {
    }

    private final PortalIndexReader index;
    private final PortalDocuments documents;
    private final ObjectProvider<ModuleProvisioner> provisioner;

    public PortalInvoiceService(PortalIndexReader index, PortalDocuments documents,
                                ObjectProvider<ModuleProvisioner> provisioner) {
        this.index = index;
        this.documents = documents;
        this.provisioner = provisioner;
    }

    /**
     * @param phone   E.164 phone of the signed-in client (from its session, never from the request)
     * @param after   position after which the page starts, null = first page
     * @param size    page size, 1–{@value #MAX_LIMIT} (checked by the caller)
     * @param baseUrl absolute base of the links
     */
    public Page list(String phone, PortalCursor after, int size, String baseUrl) {
        if (size < 1 || size > MAX_LIMIT) {
            throw new IllegalArgumentException("portal page size out of range");
        }
        List<PortalIndexReader.Row> rows = index.page(phone, after, size + 1);
        boolean more = rows.size() > size;
        List<PortalIndexReader.Row> page = more ? rows.subList(0, size) : rows;
        if (page.isEmpty()) {
            return new Page(List.of(), null);
        }
        String next = more ? page.get(page.size() - 1).cursor().encode() : null;

        Map<String, List<UUID>> idsBySchema = new LinkedHashMap<>();
        Map<String, Boolean> shown = new HashMap<>();
        for (PortalIndexReader.Row row : page) {
            if (shown.computeIfAbsent(row.tenantSchema(), this::merchantShowsDocuments)) {
                idsBySchema.computeIfAbsent(row.tenantSchema(), s -> new ArrayList<>()).add(row.invoiceId());
            }
        }
        if (idsBySchema.isEmpty()) {
            return new Page(List.of(), next);
        }
        Map<String, PortalDocument> byKey = new HashMap<>();
        for (PortalDocument d : documents.find(idsBySchema, baseUrl)) {
            byKey.put(key(d.tenantSchema(), d.invoiceId()), d);
        }
        List<PortalDocument> items = new ArrayList<>(byKey.size());
        for (PortalIndexReader.Row row : page) {
            PortalDocument d = byKey.get(key(row.tenantSchema(), row.invoiceId()));
            if (d != null) {
                items.add(d);
            }
        }
        return new Page(items, next);
    }

    /** The merchant's invoice tables are up to date; a failure hides the merchant from this page. */
    private boolean merchantShowsDocuments(String schema) {
        try {
            ModuleProvisioner p = provisioner.getIfAvailable();
            if (p != null) {
                p.ensureProvisioned(schema, INVOICING);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Client portal: a merchant's invoices left out ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String key(String schema, UUID id) {
        return schema + '/' + id;
    }
}
