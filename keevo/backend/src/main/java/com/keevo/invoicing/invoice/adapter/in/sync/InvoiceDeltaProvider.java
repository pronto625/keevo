package com.keevo.invoicing.invoice.adapter.in.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleProvisioner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Story 15.5b — the {@code invoices} delta of the sync pull: the tenant's invoices changed since the device's cursor,
 * each one exactly as the REST API returns it ({@link InvoiceResponseDto}: number, status and French label, lines,
 * totals, payment, sendings, credit notes, « Remplacée » / « Entièrement créditée » flags, compliance warnings of a
 * draft) so the app stores one shape whatever the source. Keyset paged on ({@code updated_at}, id as text) —
 * {@code idx_invoices_sync_keyset}. Owned by {@code invoicing}: skipped by the pull while the module is DISABLED
 * (READ_ONLY still pulls). A tenant whose invoicing tables were never provisioned has no delta (empty page, done).
 * A deleted draft is sent as a tombstone {@code {id, deleted: true, updatedAt}}. Tables of an older module version are
 * migrated to the latest first ({@link ModuleProvisioner#ensureProvisioned}).
 */
@Component
public class InvoiceDeltaProvider implements DeltaEntityProvider {

    /** Key of the delta in the pull response. */
    public static final String ENTITY_KEY = "invoices";

    /** Keyset cursor column; the {@code invoices} sync keyset index leads with it. */
    static final String CURSOR = InvoiceRepository.SYNC_CURSOR;

    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {
    };

    private final InvoiceRepository invoices;
    private final InvoiceQueryUseCase queries;
    private final InvoicingSettingsUseCase settings;
    private final ObjectMapper objectMapper;
    private final ModuleProvisioner provisioner;

    public InvoiceDeltaProvider(InvoiceRepository invoices, InvoiceQueryUseCase queries,
                                InvoicingSettingsUseCase settings, ObjectMapper objectMapper,
                                ModuleProvisioner provisioner) {
        this.provisioner = provisioner;
        this.invoices = invoices;
        this.queries = queries;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityKey() {
        return ENTITY_KEY;
    }

    @Override
    public String moduleId() {
        return InvoicingModule.ID;
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        if (!invoices.tablesExist()) {
            return new Page(List.of(), null);
        }
        // Tables provisioned at an older module version (e.g. before V7 deleted_at): brought to the latest one
        // first, like a gated REST call or a sync operation (cached once up to date; failure → SERVICE_UNAVAILABLE,
        // the pull then keeps its cursor and retries).
        provisioner.ensureProvisioned(TenantContext.getCurrentTenant(), InvoicingModule.ID);
        // one extra row tells whether another page exists
        List<InvoiceRepository.DeltaItem> found = invoices.delta(since, after == null ? null : after.cursor(),
                after == null ? null : after.id(), limit + 1);
        boolean more = found.size() > limit;
        List<InvoiceRepository.DeltaItem> page = more ? found.subList(0, limit) : found;
        if (page.isEmpty()) {
            return new Page(List.of(), null);
        }
        List<Invoice> live = page.stream().filter(d -> !d.deleted()).map(InvoiceRepository.DeltaItem::invoice)
                .toList();
        Map<java.util.UUID, InvoiceQueryUseCase.Credits> credits = live.isEmpty() ? Map.of() : queries.credits(live);
        InvoicingSettings current = live.isEmpty() ? null : settings.current();
        List<Map<String, Object>> rows = page.stream().map(d -> d.deleted() ? tombstone(d.invoice())
                : objectMapper.convertValue(InvoiceResponseDto.fromDomain(d.invoice(), current,
                        credits.getOrDefault(d.invoice().id(), InvoiceQueryUseCase.Credits.NONE).notes(),
                        credits.getOrDefault(d.invoice().id(), InvoiceQueryUseCase.Credits.NONE).fullyCredited()),
                ROW)).toList();
        Invoice last = page.get(page.size() - 1).invoice();
        return new Page(rows, more ? new Position(last.updatedAt(), last.id().toString()) : null);
    }

    /** A deleted draft: the device removes its copy ({@code {id, deleted: true, updatedAt}}). */
    private static Map<String, Object> tombstone(Invoice invoice) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", invoice.id().toString());
        row.put("deleted", true);
        row.put("updatedAt", invoice.updatedAt() == null ? null : invoice.updatedAt().toString());
        return row;
    }
}
