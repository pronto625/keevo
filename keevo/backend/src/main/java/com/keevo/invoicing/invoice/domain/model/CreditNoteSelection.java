package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;

/** Story 14.7 — one invoice line to credit partially: its {@code position} on the invoice and the quantity credited. */
public record CreditNoteSelection(int position, BigDecimal quantity) {
}
