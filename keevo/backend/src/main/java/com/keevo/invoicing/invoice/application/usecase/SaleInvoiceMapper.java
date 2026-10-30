package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.crm.api.ClientSummary;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Story 14.4 — maps a completed sale ({@link SaleCompletedEvent}) to the content of its automatic invoice. Pure: no
 * I/O, no clock.
 *
 * <ul>
 *   <li><b>Client</b>: the CRM client (name, phone, id) when the sale has one and the CRM knows it, else
 *       {@value #COUNTER_CLIENT}.</li>
 *   <li><b>Lines</b>: one per sold line — description = product name, quantity, unit price = the applied unit price
 *       (VAT included: what the client paid), treatment {@code STANDARD}.</li>
 *   <li><b>Discount</b> (sale total &lt; sum of the lines): spread over the lines in proportion to their totals
 *       (HALF_UP to the franc), the rounding remainder on the largest line. A line whose net total {@code n} is a
 *       multiple of its quantity {@code q} keeps {@code q} at {@code n / q}; otherwise it becomes quantity 1 at
 *       {@code n}, described « produit (× q) ». The lines then add up to the sale total exactly.</li>
 *   <li><b>Payment method</b>: « Espèces » for {@code CASH}, « Mobile Money » for {@code MOBILE_MONEY}.</li>
 * </ul>
 */
public final class SaleInvoiceMapper {

    /** The client of a sale without a (known) CRM client. */
    public static final String COUNTER_CLIENT = "Client comptoir";

    /** Description of a line whose product name is missing. */
    static final String UNNAMED_PRODUCT = "Article";

    private SaleInvoiceMapper() {
    }

    /** The invoice client: the CRM client when known, else the counter client (no id). */
    public static ClientInfo client(Optional<ClientSummary> crmClient) {
        return crmClient
                .filter(c -> c.name() != null && !c.name().isBlank())
                .map(c -> new ClientInfo(c.id(), c.name(), null, null, c.phone()))
                .orElseGet(() -> new ClientInfo(null, COUNTER_CLIENT, null, null, null));
    }

    /** « Espèces », « Mobile Money », or {@code null} for an unknown mode. */
    public static String paymentMethod(String paymentMode) {
        if (paymentMode == null) {
            return null;
        }
        return switch (paymentMode) {
            case "CASH" -> "Espèces";
            case "MOBILE_MONEY" -> "Mobile Money";
            default -> null;
        };
    }

    /**
     * The invoice lines of the sale, VAT included, adding up to {@code totalAmount}.
     *
     * @throws IllegalArgumentException no line, a line with a quantity &lt; 1 or a negative price, or a sale total
     *                                  that is negative or above the sum of its lines (inconsistent sale)
     */
    public static List<LineInput> lines(List<SaleCompletedEvent.Line> sold, long totalAmount) {
        if (sold == null || sold.isEmpty()) {
            throw new IllegalArgumentException("A sale without lines cannot be invoiced");
        }
        long[] gross = new long[sold.size()];
        long sum = 0;
        int largest = 0;
        for (int i = 0; i < sold.size(); i++) {
            SaleCompletedEvent.Line line = sold.get(i);
            if (line.quantity() < 1 || line.unitPrice() < 0) {
                throw new IllegalArgumentException("Invalid sale line");
            }
            gross[i] = Math.multiplyExact(line.unitPrice(), (long) line.quantity());
            sum = Math.addExact(sum, gross[i]);
            if (gross[i] > gross[largest]) {
                largest = i;
            }
        }
        long discount = sum - totalAmount;
        if (totalAmount < 0 || discount < 0) {
            throw new IllegalArgumentException("Sale total inconsistent with its lines");
        }
        long[] net = new long[sold.size()];
        long allocated = 0;
        for (int i = 0; i < sold.size(); i++) {
            long share = discount == 0 ? 0 : BigDecimal.valueOf(gross[i]).multiply(BigDecimal.valueOf(discount))
                    .divide(BigDecimal.valueOf(sum), 0, RoundingMode.HALF_UP).longValueExact();
            net[i] = gross[i] - share;
            allocated += share;
        }
        net[largest] -= discount - allocated; // the rounding remainder goes to the largest line
        if (net[largest] < 0) {
            throw new IllegalArgumentException("Sale discount cannot be spread over its lines");
        }
        List<LineInput> lines = new ArrayList<>(sold.size());
        for (int i = 0; i < sold.size(); i++) {
            SaleCompletedEvent.Line line = sold.get(i);
            String name = line.productName() == null || line.productName().isBlank()
                    ? UNNAMED_PRODUCT : line.productName().strip();
            int q = line.quantity();
            if (net[i] % q == 0) {
                lines.add(new LineInput(name, BigDecimal.valueOf(q), net[i] / q, VatTreatment.STANDARD));
            } else {
                lines.add(new LineInput(name + " (× " + q + ")", BigDecimal.ONE, net[i], VatTreatment.STANDARD));
            }
        }
        return List.copyOf(lines);
    }
}
