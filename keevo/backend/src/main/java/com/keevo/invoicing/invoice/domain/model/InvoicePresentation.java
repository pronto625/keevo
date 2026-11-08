package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 20.7 (fusion Keevo × Bref, FUS-FR21/22/23) — how a document is printed and shared, outside its legal content
 * (changing it never alters an issued invoice's lines, totals or number):
 * <ul>
 *   <li>{@code template} / {@code ticketWidth} — the layout chosen for this document ({@code null} = the tenant's
 *       default, see {@link DocumentPreferences});</li>
 *   <li>{@code clientSignature} / {@code sellerSignature} — file names of the stored signature images ({@code null} =
 *       not signed); never exposed by the API (only « signed or not »);</li>
 *   <li>{@code linkVersion} — the public link's version: a rotation bumps it and every link signed with an older one
 *       stops opening (links sent before 20.7 carry version 0);</li>
 *   <li>{@code legacyToken} / {@code legacyReference} — the Bref public token and reference ({@code BRF-XXXXXXXX}) of
 *       an imported invoice;</li>
 *   <li>{@code linkShared} — a public link of the document was handed out (with the WhatsApp sending count: a sent
 *       quote's client signature is frozen).</li>
 * </ul>
 */
public record InvoicePresentation(
        InvoiceTemplate template,
        TicketWidth ticketWidth,
        String clientSignature,
        String sellerSignature,
        int linkVersion,
        UUID legacyToken,
        String legacyReference,
        boolean linkShared) {

    /** Every document before 20.7: tenant default layout, unsigned, link version 0, not imported, never shared. */
    public static final InvoicePresentation NONE = new InvoicePresentation(null, null, null, null, 0, null, null);

    /** Not shared yet. */
    public InvoicePresentation(InvoiceTemplate template, TicketWidth ticketWidth, String clientSignature,
                               String sellerSignature, int linkVersion, UUID legacyToken, String legacyReference) {
        this(template, ticketWidth, clientSignature, sellerSignature, linkVersion, legacyToken, legacyReference,
                false);
    }

    /** {@code BRF-} + 8 upper-case hexadecimal characters (Bref's short reference). */
    public static final Pattern LEGACY_REFERENCE = Pattern.compile("BRF-[0-9A-F]{8}");

    public InvoicePresentation {
        if (linkVersion < 0) {
            throw new IllegalArgumentException("Negative link version");
        }
        if (legacyReference != null && !LEGACY_REFERENCE.matcher(legacyReference).matches()) {
            throw new IllegalArgumentException("Invalid legacy reference");
        }
    }

    /** The stored signature file of {@code role}, {@code null} when not signed. */
    public String signature(SignatureRole role) {
        return role == SignatureRole.CLIENT ? clientSignature : sellerSignature;
    }

    public boolean signed(SignatureRole role) {
        return signature(role) != null;
    }

    /** The template printed: this document's, else the tenant's default; {@code null} = Keevo standard layout. */
    public InvoiceTemplate effectiveTemplate(DocumentPreferences preferences) {
        if (template != null) {
            return template;
        }
        return preferences == null ? null : preferences.template();
    }

    /** The paper of a narrow layout: this document's, else the tenant's, else {@link TicketWidth#DEFAULT}. */
    public TicketWidth effectiveWidth(DocumentPreferences preferences) {
        if (ticketWidth != null) {
            return ticketWidth;
        }
        return preferences == null || preferences.ticketWidth() == null ? TicketWidth.DEFAULT
                : preferences.ticketWidth();
    }

    /**
     * The Bref reference of a Bref invoice id (« BRF- » + its first 8 hexadecimal characters, upper case) — what the
     * import stores in {@code legacyReference}.
     */
    public static String legacyReferenceOf(UUID brefInvoiceId) {
        return brefInvoiceId == null ? null
                : "BRF-" + brefInvoiceId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
