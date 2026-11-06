package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A credit note — « avoir » (Story 14.7). Created already issued (no draft): it references an {@code ISSUED} or
 * {@code PAID} invoice for all of its lines or some of them, carries its own number of the gapless series
 * {@code AV-<yyyy>-<000001>} and is never modified nor deleted. The invoice it credits keeps its number and content.
 *
 * <p>Amounts are positive here and in storage; the « avoir » meaning and the leading minus sign are presentation.
 *
 * <ul>
 *   <li><b>Full</b> credit note: every invoice line that still has a quantity to credit, for that remaining quantity
 *       and the remaining amounts (HT, VAT, TTC) exactly.</li>
 *   <li><b>Partial</b>: {@code [{position, quantity}]}, quantity &gt; 0 with at most 3 decimals and at most the
 *       remaining quantity of the line. Crediting the whole remaining quantity takes the remaining amounts exactly (no
 *       rounding drift: the credit notes of a line always add up to the line); otherwise the amounts are computed like
 *       the invoice line — same unit price, treatment and VAT snapshot of the invoice, HALF_UP to the franc.</li>
 * </ul>
 *
 * <p>Story 20.5 — on an invoice with a discount or a delivery fee, the line amounts no longer add up to the document:
 * only a <b>full</b> credit note is accepted (every line, its whole quantity, nothing credited before) and it credits
 * exactly the invoice's totals ({@link #creditedTotals}); a partial one → 409 {@code INVOICE_INVALID_TRANSITION}
 * « Avoir partiel impossible sur une facture avec remise ou livraison : établissez un avoir total ».
 */
public record CreditNote(
        UUID id,
        String number,
        UUID invoiceId,
        String invoiceNumber,
        String reason,
        List<CreditNoteLine> lines,
        InvoiceTotals totals,
        Instant issuedAt,
        UUID createdBy,
        String sourceEvent) {

    /** Column {@code credit_notes.reason}. */
    public static final int MAX_REASON = 300;

    public CreditNote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code AV-2026-000001}. */
    public static String number(int year, long sequence) {
        return String.format("AV-%04d-%06d", year, sequence);
    }

    /** The trimmed reason; blank → 422 {@code VALIDATION_ERROR} (French), too long → 422 too. */
    public static String requireReason(String reason) {
        String why = InvoicingSettings.trimToNull(reason);
        if (why == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "A credit note reason is required",
                    Map.of("reason", "Le motif de l'avoir est requis"));
        }
        if (why.length() > MAX_REASON) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Credit note reason too long",
                    Map.of("reason", "Le motif de l'avoir ne doit pas dépasser " + MAX_REASON + " caractères"));
        }
        return why;
    }

    /**
     * The lines of a new credit note on {@code invoice}, checked before any number is allocated.
     *
     * @param credited  every line of the credit notes already issued on the invoice
     * @param selection {@code null} = a full credit note (everything still to credit); else the lines to credit
     * @throws DomainException {@code INVOICE_INVALID_TRANSITION} (409) for a draft, a cancelled or an already fully
     *                         credited invoice; {@code CREDIT_NOTE_INVALID} (422, French reason) for an empty selection,
     *                         an unknown / repeated position, a bad quantity, a line already fully credited, a
     *                         quantity above what remains to credit, or a credit note of 0 F (it would consume a
     *                         number for nothing)
     */
    public static List<CreditNoteLine> creditLines(Invoice invoice, List<CreditNoteLine> credited,
                                                   List<CreditNoteSelection> selection) {
        invoice.assertInvoice(); // Story 20.3 — a quote is never credited
        if (invoice.status() != InvoiceStatus.ISSUED && invoice.status() != InvoiceStatus.PAID) {
            throw new DomainException(ErrorCode.INVOICE_INVALID_TRANSITION, "Credit note not allowed",
                    Map.of("status", invoice.status().label().toLowerCase(java.util.Locale.FRENCH)));
        }
        Map<Integer, Remaining> remaining = remaining(invoice, credited == null ? List.of() : credited);
        if (remaining.values().stream().noneMatch(r -> r.quantity().signum() > 0)) {
            throw fullyCredited();
        }
        if (invoice.terms().hasAdjustments() && !coversEverything(invoice, credited, selection)) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, PARTIAL_ON_ADJUSTED,
                    Map.of("status", "avec remise ou livraison"));
        }
        List<CreditNoteLine> lines = new ArrayList<>();
        if (selection == null) {
            for (InvoiceLine line : invoice.lines()) {
                Remaining r = remaining.get(line.position());
                if (r.quantity().signum() > 0) {
                    lines.add(r.asLine(line));
                }
            }
        } else {
            if (selection.isEmpty()) {
                throw invalid("choisissez au moins une ligne à créditer");
            }
            Set<Integer> seen = new HashSet<>();
            List<CreditNoteSelection> sorted = new ArrayList<>(selection);
            sorted.sort(Comparator.comparingInt(CreditNoteSelection::position));
            for (CreditNoteSelection s : sorted) {
                int p = s.position();
                InvoiceLine line = invoice.lines().stream().filter(l -> l.position() == p).findFirst()
                        .orElseThrow(() -> invalid("la ligne " + p + " n'existe pas sur la facture"));
                if (!seen.add(p)) {
                    throw invalid("la ligne " + p + " est indiquée plusieurs fois");
                }
                BigDecimal quantity = s.quantity();
                if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
                    throw invalid("la quantité de la ligne " + p
                            + " doit être supérieure à 0, avec au plus 3 décimales");
                }
                Remaining r = remaining.get(p);
                if (r.quantity().signum() <= 0) {
                    throw invalid("la ligne " + p + " est déjà entièrement créditée");
                }
                int cmp = quantity.compareTo(r.quantity());
                if (cmp > 0) {
                    throw invalid("la quantité de la ligne " + p + " dépasse la quantité restant à créditer ("
                            + plain(r.quantity()) + ")");
                }
                lines.add(cmp == 0 ? r.asLine(line) : partial(invoice, line, quantity, r));
            }
        }
        List<CreditNoteLine> numbered = new ArrayList<>(lines.size());
        int position = 1;
        for (CreditNoteLine l : lines) {
            numbered.add(new CreditNoteLine(position++, l.invoicePosition(), l.description(), l.quantity(),
                    l.unitPrice(), l.treatment(), l.vatRate(), l.totalHt(), l.totalVat(), l.totalTtc()));
        }
        if (creditedTotals(invoice, credited, numbered).totalTtc() == 0) {
            // a credit note of 0 F would consume a number of the gapless series for nothing
            throw invalid("le montant de l'avoir serait nul");
        }
        return List.copyOf(numbered);
    }

    /** The issued credit note (number allocated by the caller in the same transaction), nothing credited before. */
    public static CreditNote issue(UUID id, String number, Invoice invoice, String reason, List<CreditNoteLine> lines,
                                   UUID createdBy, Instant now, String sourceEvent) {
        return issue(id, number, invoice, reason, List.of(), lines, createdBy, now, sourceEvent);
    }

    /**
     * The issued credit note; {@code credited} = the lines of the credit notes already issued on the invoice (Story
     * 20.5: they decide what a credit note closing an invoice with document-level totals credits).
     */
    public static CreditNote issue(UUID id, String number, Invoice invoice, String reason,
                                   List<CreditNoteLine> credited, List<CreditNoteLine> lines, UUID createdBy,
                                   Instant now, String sourceEvent) {
        return new CreditNote(id, number, invoice.id(), invoice.number(), requireReason(reason), lines,
                creditedTotals(invoice, credited, lines), now, createdBy, sourceEvent);
    }

    /** Story 20.5 — the French refusal of a partial credit note on an invoice with a discount or a delivery fee. */
    public static final String PARTIAL_ON_ADJUSTED =
            "Avoir partiel impossible sur une facture avec remise ou livraison : établissez un avoir total";

    /**
     * Story 20.5 — whether the invoice's totals are document-level rather than the sums of its lines: a discount or a
     * delivery fee, or prices without VAT (VAT rounded once on the document).
     */
    public static boolean documentLevelTotals(Invoice invoice) {
        return invoice.terms().hasAdjustments() || !invoice.pricesIncludeVat();
    }

    /**
     * Story 20.5 — the totals a credit note of {@code lines} on {@code invoice} credits, {@code credited} being the lines
     * of the credit notes issued before: the sums of its lines, except on an invoice with document-level totals
     * ({@link #documentLevelTotals}) for the credit note that leaves nothing to credit — the first and only one credits
     * exactly the invoice's totals, a last one exactly what remains (the invoice's totals minus the previous credit
     * notes), so the credit notes of an invoice always add up to it.
     */
    public static InvoiceTotals creditedTotals(Invoice invoice, List<CreditNoteLine> credited,
                                               List<CreditNoteLine> lines) {
        List<CreditNoteLine> before = credited == null ? List.of() : credited;
        if (!documentLevelTotals(invoice)) {
            return totals(lines);
        }
        List<CreditNoteLine> after = new ArrayList<>(before);
        after.addAll(lines);
        if (!fullyCredited(invoice, after)) {
            return totals(lines);
        }
        if (before.isEmpty()) {
            return invoice.totals();
        }
        InvoiceTotals t = invoice.totals();
        InvoiceTotals done = totals(before);
        long ht = Math.max(0, t.totalHt() - done.totalHt());
        long vat = Math.max(0, t.totalVat() - done.totalVat());
        long ttc = Math.max(0, t.totalTtc() - done.totalTtc());
        return new InvoiceTotals(ht, vat, ttc, ht, 0, 0);
    }

    /** Story 20.5 — nothing credited yet and the selection ({@code null} = full) takes every line in full. */
    private static boolean coversEverything(Invoice invoice, List<CreditNoteLine> credited,
                                            List<CreditNoteSelection> selection) {
        if (credited != null && !credited.isEmpty()) {
            return false;
        }
        if (selection == null) {
            return true;
        }
        Map<Integer, BigDecimal> asked = new HashMap<>();
        for (CreditNoteSelection s : selection) {
            if (s.quantity() == null || asked.put(s.position(), s.quantity()) != null) {
                return false;
            }
        }
        if (asked.size() != invoice.lines().size()) {
            return false;
        }
        for (InvoiceLine line : invoice.lines()) {
            BigDecimal q = asked.get(line.position());
            if (q == null || q.compareTo(line.quantity()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Sums of the lines. */
    public static InvoiceTotals totals(List<CreditNoteLine> lines) {
        long ht = 0;
        long vat = 0;
        long ttc = 0;
        for (CreditNoteLine l : lines) {
            ht = Math.addExact(ht, l.totalHt());
            vat = Math.addExact(vat, l.totalVat());
            ttc = Math.addExact(ttc, l.totalTtc());
        }
        return new InvoiceTotals(ht, vat, ttc);
    }

    /** The TTC amount still to credit on {@code invoice} after {@code credited} (0 = nothing a credit note can take). */
    public static long remainingTtc(Invoice invoice, List<CreditNoteLine> credited) {
        if (documentLevelTotals(invoice)) { // Story 20.5 — what the document owes minus what was credited
            return fullyCredited(invoice, credited) ? 0
                    : Math.max(0, invoice.totals().totalTtc() - totals(credited == null ? List.of() : credited)
                    .totalTtc());
        }
        return remaining(invoice, credited == null ? List.of() : credited).values().stream()
                .filter(r -> r.quantity().signum() > 0).mapToLong(Remaining::ttc).sum();
    }

    /** Whether nothing remains to credit on {@code invoice} after {@code credited}. */
    public static boolean fullyCredited(Invoice invoice, List<CreditNoteLine> credited) {
        return remaining(invoice, credited == null ? List.of() : credited).values().stream()
                .noneMatch(r -> r.quantity().signum() > 0);
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────────

    /** What remains to credit on one invoice line. */
    private record Remaining(BigDecimal quantity, long ht, long vat, long ttc) {
        CreditNoteLine asLine(InvoiceLine line) {
            return new CreditNoteLine(0, line.position(), line.description(), quantity, line.unitPrice(),
                    line.treatment(), line.vatRate(), ht, vat, ttc);
        }
    }

    private static Map<Integer, Remaining> remaining(Invoice invoice, List<CreditNoteLine> credited) {
        Map<Integer, Remaining> remaining = new HashMap<>();
        for (InvoiceLine line : invoice.lines()) {
            remaining.put(line.position(), new Remaining(line.quantity(), line.totalHt(), line.totalVat(),
                    line.totalTtc()));
        }
        for (CreditNoteLine c : credited) {
            Remaining r = remaining.get(c.invoicePosition());
            if (r != null) {
                remaining.put(c.invoicePosition(), new Remaining(r.quantity().subtract(c.quantity()),
                        r.ht() - c.totalHt(), r.vat() - c.totalVat(), r.ttc() - c.totalTtc()));
            }
        }
        return remaining;
    }

    /**
     * A part of the line computed like the invoice line, kept within what remains (a rounding of the last franc can
     * never make the credit notes of a line exceed it).
     */
    private static CreditNoteLine partial(Invoice invoice, InvoiceLine line, BigDecimal quantity, Remaining r) {
        InvoiceLine computed;
        try {
            computed = VatCalculator.lines(List.of(new LineInput(line.description(), quantity, line.unitPrice(),
                    line.treatment())), invoice.vatRegistered(), invoice.vatRate(), invoice.pricesIncludeVat()).get(0);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw invalid("la quantité de la ligne " + line.position() + " est hors limites");
        }
        long ttc = Math.max(0, Math.min(computed.totalTtc(), r.ttc()));
        long ht = Math.max(Math.max(0, ttc - r.vat()), Math.min(computed.totalHt(), Math.min(r.ht(), ttc)));
        long vat = ttc - ht;
        return new CreditNoteLine(0, line.position(), line.description(), quantity.setScale(3,
                java.math.RoundingMode.UNNECESSARY), line.unitPrice(), line.treatment(), line.vatRate(), ht, vat, ttc);
    }

    private static String plain(BigDecimal q) {
        BigDecimal s = q.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.toPlainString().replace('.', ',');
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.CREDIT_NOTE_INVALID, "Invalid credit note", Map.of("reason", reason));
    }

    private static DomainException fullyCredited() {
        return new DomainException(ErrorCode.INVOICE_INVALID_TRANSITION, "Invoice already fully credited",
                Map.of("status", "déjà entièrement couverte par des avoirs"));
    }
}
