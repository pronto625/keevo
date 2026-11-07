package com.keevo.catalog.api;

import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.module.Capability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional capability of the {@code catalog} module (Story 20.6): the catalogue products the lines of an issued
 * document stand for, found or created (Bref « dérivation » : a sale records its products, FUS-FR16). Never inject
 * it: resolve it per tenant with {@code CapabilityRegistry.find(ProductLookupPort.class, tenantSchema)}, empty while
 * {@code catalog} is off for the tenant — the document then keeps its free-text lines.
 *
 * <p>Same tenant convention as the other {@code catalog.api} ports: {@code tenantSchema} must be the current tenant
 * context ({@link IllegalStateException} otherwise).
 */
public interface ProductLookupPort extends Capability {

    /** Column {@code products.name}: a longer line description is cut to this length to name the product. */
    int MAX_NAME_LENGTH = 200;

    /** A document line to derive a product from: its description, reference (optional) and unit price. */
    record DocumentLine(String description, String reference, long unitPrice) {
    }

    /**
     * The products of {@code lines}, keyed by {@link #keyOf} of each line: found by product key or by name (a merged
     * product answers with the product it was merged into), else created without stock tracking at the line's unit
     * price ({@code price_manual = false}). A found, non-archived product whose price was not typed by a person takes
     * the line's price (the last sale price, Bref). Lines without usable description, lines beyond the plan's product
     * limit and lines whose product cannot be created are simply absent from the map.
     *
     * <p>Set-based (a constant number of queries whatever the number of lines), under the tenant's catalogue lock,
     * in the caller's transaction (required). Never throws for a business reason.
     */
    Map<String, UUID> findOrCreateForDocument(String tenantSchema, List<DocumentLine> lines, UUID actorId);

    /**
     * The key {@link #findOrCreateForDocument} answers a line under ({@code bc:<barcode>} for a barcode-like
     * reference, else {@code nm:<normalised name>} of the description cut to {@value #MAX_NAME_LENGTH} characters);
     * {@code null} for a blank description.
     */
    static String keyOf(String description, String reference) {
        String name = productName(description);
        return name == null ? null : ProductKeys.key(name, reference);
    }

    /** The product name a line description gives: trimmed, cut to {@value #MAX_NAME_LENGTH}; {@code null} if blank. */
    static String productName(String description) {
        if (description == null) return null;
        String trimmed = description.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH).strip() : trimmed;
    }
}
