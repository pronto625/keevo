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
        if (invoice.status() != InvoiceStatus.ISSUED && invoice.status() != InvoiceStatus.PAID) {
            throw new DomainException(ErrorCode.INVOICE_INVALID_TRANSITION, "Credit note not allowed",
                    Map.of("status", invoice.status().label().toLowerCase(java.util.Locale.FRENCH)));
        }
        Map<Integer, Remaining> remaining = remaining(invoice, credited == null ? List.of() : credited);
        if (remaining.values().stream().noneMatch(r -> r.quantity().signum() > 0)) {
            throw fullyCredited();
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
        if (totals(numbered).totalTtc() == 0) {
            // a credit note of 0 F would consume a number of the gapless series for nothing
            throw invalid("le montant de l'avoir serait nul");
        }
        return List.copyOf(numbered);
    }

    /** The issued credit note (number allocated by the caller in the same transaction). */
    public static CreditNote issue(UUID id, String number, Invoice invoice, String reason, List<CreditNoteLine> lines,
                                   UUID createdBy, Instant now, String sourceEvent) {
        return new CreditNote(id, number, invoice.id(), invoice.number(), requireReason(reason), lines,
                totals(lines), now, createdBy, sourceEvent);
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
