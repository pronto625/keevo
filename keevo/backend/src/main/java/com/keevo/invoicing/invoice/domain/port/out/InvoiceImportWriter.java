package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.PaymentState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.4 — set-based persistence of the Bref documents import, on the bound tenant schema: one statement per
 * table, rows already present left as they are ({@code ON CONFLICT DO NOTHING}).
 */
public interface InvoiceImportWriter {

    /** The single settings row, written only when absent. */
    void insertSettingsIfAbsent(InvoicingSettings settings, Instant at);

    /** The single document-preferences row with this public-link lifetime, written only when absent. */
    void insertPreferencesIfAbsent(int publicLinkDays, Instant at);

    Set<UUID> insertDocuments(List<Document> documents);

    Set<UUID> insertLines(List<Line> lines);

    Set<UUID> insertPayments(List<Payment> payments);

    /** What the collection state of an invoice is computed from. */
    record CollectionInput(UUID id, boolean quote, String status, long totalTtc, LocalDate dueDate,
                           List<InvoicePayment> rows) {
    }

    /** The invoices {@code ids} with their stored payment rows (two queries). */
    List<CollectionInput> collections(Collection<UUID> ids);

    /** Writes the collection state of each invoice ({@code null} = none), one statement, timestamps untouched. */
    void updateStates(Map<UUID, PaymentState> states);
}
