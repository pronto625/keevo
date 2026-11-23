package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.application.usecase.InvoicePresentationUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Story 22.19 (D21) — the signatures sent inside an invoice body ({@code sellerSignature} / {@code clientSignature}:
 * PNG data URLs, ≤ {@value #MAX_BYTES} bytes each once decoded) are stored through the Story 20.7 signature use case
 * (re-encoded, drawn in the PDF signature boxes); single-document answers carry them back as data URLs.
 */
@Component
public class InvoiceSignatureIntake {

    /** 200 KB per signature image. */
    public static final int MAX_BYTES = 200 * 1024;
    static final String INVALID = "La signature doit être une image PNG de 200 Ko au plus";

    private final InvoicePresentationUseCase presentations;

    public InvoiceSignatureIntake(InvoicePresentationUseCase presentations) {
        this.presentations = presentations;
    }

    /** The decoded images, checked before any write (a refused signature creates nothing). */
    public record Signatures(byte[] seller, byte[] client) {
        public boolean none() {
            return seller == null && client == null;
        }
    }

    public static Signatures decode(String seller, String client) {
        return new Signatures(decodeOne(seller, "sellerSignature"), decodeOne(client, "clientSignature"));
    }

    /**
     * The draft write and its signatures in ONE transaction: a refused signature rolls the draft write back (and the
     * signature files go with the rollback, see Story 20.7).
     */
    @org.springframework.transaction.annotation.Transactional
    public Invoice writeSigned(java.util.function.Supplier<Invoice> draftWrite, Signatures signatures, UUID actorId,
                               String tenantSchema) {
        Invoice written = draftWrite.get();
        return signatures.none() ? written : apply(written.id(), signatures, written, actorId, tenantSchema);
    }

    /** Stores the given signatures on document {@code id} (absent = unchanged); returns the document after. */
    public Invoice apply(UUID id, Signatures signatures, Invoice current, UUID actorId, String tenantSchema) {
        Invoice result = current;
        if (signatures.seller() != null) {
            result = presentations.sign(id, SignatureRole.SELLER, signatures.seller(), actorId, tenantSchema);
        }
        if (signatures.client() != null) {
            result = presentations.sign(id, SignatureRole.CLIENT, signatures.client(), actorId, tenantSchema);
        }
        return result;
    }

    /** The answer with the stored signature images as data URLs. */
    public InvoiceResponseDto withImages(InvoiceResponseDto dto, String tenantSchema) {
        if (!dto.sellerSigned() && !dto.clientSigned()) {
            return dto;
        }
        return dto.withSignatures(image(dto, SignatureRole.SELLER, dto.sellerSigned(), tenantSchema),
                image(dto, SignatureRole.CLIENT, dto.clientSigned(), tenantSchema));
    }

    private String image(InvoiceResponseDto dto, SignatureRole role, boolean signed, String tenantSchema) {
        if (!signed) {
            return null;
        }
        try {
            return presentations.signature(dto.id(), role, tenantSchema).dataUri();
        } catch (DomainException e) {
            return null; // file gone: answered as not drawn, never a failure
        }
    }

    static byte[] decodeOne(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        int comma = v.indexOf(',');
        if (v.startsWith("data:")) {
            String head = v.substring(0, Math.max(comma, 0)).toLowerCase(java.util.Locale.ROOT);
            if (comma < 0 || !head.startsWith("data:image/png") || !head.endsWith(";base64")) {
                throw invalid(field);
            }
            v = v.substring(comma + 1);
        }
        // base64 of 200 KB is ~273 KB: refused before decoding anything bigger
        if (v.length() > (MAX_BYTES / 3 + 1) * 4 + 4) {
            throw invalid(field);
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(v);
        } catch (IllegalArgumentException e) {
            throw invalid(field);
        }
        boolean png = bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G';
        if (!png || bytes.length > MAX_BYTES) {
            throw invalid(field);
        }
        return bytes;
    }

    private static DomainException invalid(String field) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, INVALID,
                Map.of("field", field, "reason", INVALID));
    }
}
