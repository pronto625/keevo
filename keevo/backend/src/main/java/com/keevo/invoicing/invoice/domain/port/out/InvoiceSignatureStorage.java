package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.SignatureRole;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.7 — where signature images live: same pattern as the store and company logos (local disk today, an object
 * store tomorrow). Each upload is a new file named {@code <invoice id>-<role>-<unique hex>.png|jpg}; the tenant schema
 * is validated on every access (no path escape, no cross-tenant read). The invoice row keeps only the file name.
 */
public interface InvoiceSignatureStorage {

    /** A stored image: {@code image/png} or {@code image/jpeg}. */
    record StoredImage(byte[] content, String contentType) {

        /** {@code data:} URI for a PDF template. */
        public String dataUri() {
            return "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(content);
        }
    }

    /** Writes the image under a new unique name (atomically) and returns that name. */
    String store(String tenantSchema, UUID invoiceId, SignatureRole role, byte[] content, String contentType)
            throws IOException;

    /** The image stored under {@code fileName}; empty when missing or not a signature file name. */
    Optional<StoredImage> read(String tenantSchema, String fileName) throws IOException;

    /** Removes one file; missing = no-op; never throws (failures are logged). */
    void delete(String tenantSchema, String fileName);
}
