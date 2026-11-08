package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureNormalizer;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSignatureStorage.StoredImage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR21/22) — how a document is printed, outside its legal content:
 * <ul>
 *   <li>its layout (one of the 8 Bref templates, ticket paper) — any status, issued invoices included (a layout is
 *       not a legal mention);</li>
 *   <li>its client and seller signatures — stored like the logos (re-encoded PNG / JPEG file, the row keeps the file
 *       name). Signing a cancelled invoice or a converted / cancelled quote is refused; on an issued invoice a
 *       signature is added once and never replaced nor removed (it is evidence of the client's agreement); a draft's
 *       signature can be replaced or removed;</li>
 *   <li>the tenant's defaults (layout, paper, public link lifetime).</li>
 * </ul>
 * Pattern of the logos (Ports &amp; Adapters + file swap after commit): the file the row no longer points to is deleted
 * only after the commit, the new file after a rollback. Cost: one locked invoice read, one presentation read, one
 * UPDATE, one file write.
 */
@Service
public class InvoicePresentationUseCase {

    private final InvoiceRepository invoices;
    private final InvoicePresentationRepository presentations;
    private final InvoiceSignatureStorage storage;
    private final InvoiceSignatureNormalizer normalizer;
    private final int maxBytes;
    private final Clock clock;

    public InvoicePresentationUseCase(InvoiceRepository invoices, InvoicePresentationRepository presentations,
                                      InvoiceSignatureStorage storage, InvoiceSignatureNormalizer normalizer,
                                      @Value("${keevo.invoice-signature.max-bytes:204800}") int maxBytes,
                                      Clock clock) {
        this.invoices = invoices;
        this.presentations = presentations;
        this.storage = storage;
        this.normalizer = normalizer;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    /** The document's layout ({@code null} = the tenant default); any status. */
    @Transactional
    public Invoice updateLayout(UUID id, InvoiceTemplate template, TicketWidth width) {
        Invoice invoice = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        presentations.updateLayout(id, template, width, clock.instant());
        return invoices.findById(invoice.id()).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    /** Stores the signature of {@code role} (PNG / JPEG ≤ max bytes). */
    @Transactional
    public Invoice sign(UUID id, SignatureRole role, byte[] upload, String tenantSchema) {
        // cheap checks and the re-encoding first: no database work for a refused upload
        if (upload == null || upload.length == 0) {
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_INVALID, "Empty signature");
        }
        if (upload.length > maxBytes) {
            throw new DomainException(ErrorCode.INVOICE_SIGNATURE_TOO_LARGE,
                    "Signature of " + upload.length + " bytes over " + maxBytes);
        }
        StoredImage image = normalizer.normalize(upload);
        Invoice invoice = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        assertSignable(invoice);
        InvoicePresentation presentation = presentations.find(id).orElse(InvoicePresentation.NONE);
        String previous = presentation.signature(role);
        if (previous != null && invoice.status() != InvoiceStatus.DRAFT) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION,
                    "Ce document émis est déjà signé : sa signature ne peut pas être remplacée",
                    Map.of("status", "déjà signée"));
        }
        String fileName;
        try {
            fileName = storage.store(tenantSchema, id, role, image.content(), image.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Signature could not be stored", e);
        }
        onCompletion(() -> {
            if (previous != null && !previous.equals(fileName)) storage.delete(tenantSchema, previous);
        }, () -> storage.delete(tenantSchema, fileName));
        presentations.updateSignature(id, role, fileName, clock.instant());
        return invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    /** Removes the signature of {@code role} — a draft (invoice or quote) only; not signed = no-op. */
    @Transactional
    public Invoice unsign(UUID id, SignatureRole role, String tenantSchema) {
        Invoice invoice = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        String previous = presentations.find(id).orElse(InvoicePresentation.NONE).signature(role);
        if (previous == null) {
            return invoice;
        }
        if (invoice.status() != InvoiceStatus.DRAFT) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION,
                    "La signature d'un document émis ne peut pas être retirée", Map.of("status", "émise"));
        }
        presentations.updateSignature(id, role, null, clock.instant());
        onCompletion(() -> storage.delete(tenantSchema, previous), () -> { });
        return invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    /** The stored signature image of {@code role}. @throws DomainException INVOICE_SIGNATURE_NOT_FOUND */
    @Transactional(readOnly = true)
    public StoredImage signature(UUID id, SignatureRole role, String tenantSchema) {
        String file = presentations.find(id).orElseThrow(InvoiceQueryUseCase::notFound).signature(role);
        if (file == null) {
            throw notSigned();
        }
        try {
            return storage.read(tenantSchema, file).orElseThrow(InvoicePresentationUseCase::notSigned);
        } catch (IOException e) {
            throw new UncheckedIOException("Signature could not be read", e);
        }
    }

    @Transactional(readOnly = true)
    public DocumentPreferences preferences() {
        return presentations.preferences();
    }

    @Transactional
    public DocumentPreferences updatePreferences(DocumentPreferences preferences, UUID actorId) {
        presentations.savePreferences(preferences, actorId, clock.instant());
        return presentations.preferences();
    }

    /** Cancelled invoice, converted or cancelled quote → 409 French. */
    static void assertSignable(Invoice invoice) {
        InvoiceStatus s = invoice.status();
        if (s == InvoiceStatus.CANCELLED || s == InvoiceStatus.CONVERTED) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION,
                    invoice.isQuote() ? "Ce devis a été converti ou annulé : il ne peut plus être signé"
                            : "Cette facture est annulée : elle ne peut plus être signée",
                    Map.of("status", s.label(invoice.kind()).toLowerCase(java.util.Locale.FRENCH)));
        }
    }

    private static DomainException notSigned() {
        return new DomainException(ErrorCode.INVOICE_SIGNATURE_NOT_FOUND, "No such signature");
    }

    /** {@code onCommit} after a commit, {@code onRollback} after a rollback; without a transaction: commit at once. */
    private static void onCompletion(Runnable onCommit, Runnable onRollback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) onCommit.run();
                    else if (status == STATUS_ROLLED_BACK) onRollback.run();
                }
            });
        } else {
            onCommit.run();
        }
    }
}
