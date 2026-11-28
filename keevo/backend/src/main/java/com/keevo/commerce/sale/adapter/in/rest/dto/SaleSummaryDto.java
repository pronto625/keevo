package com.keevo.commerce.sale.adapter.in.rest.dto;

/**
 * Story P03 — revenue of a period: {@code amount} (net revenue, FCFA), {@code count} (sales and invoices),
 * {@code paidAmount} (settled part).
 */
public record SaleSummaryDto(long amount, long count, long paidAmount) {}
