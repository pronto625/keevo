/**
 * Client portal capability of the core (Story 21.7, FUS-FR29): the end client of the merchants finds, by phone, every
 * invoice issued to that number.
 *
 * <ul>
 *   <li>{@link com.keevo.shared.capability.portal.InvoicePhoneIndex} — the cross-tenant index {@code (phone, tenant,
 *       invoice)}: implemented by the {@code client-portal} module (it owns {@code public.invoice_phone_index}),
 *       fed by {@code invoicing} in the issue transaction (and by the Bref import, 24.6).</li>
 *   <li>{@link com.keevo.shared.capability.portal.PortalDocuments} — the minimum fields of documents read in their
 *       own tenant schemas: implemented by {@code invoicing} (it owns the invoice tables), consumed by the portal.</li>
 * </ul>
 * Neither module depends on the other: both depend on {@code shared} only.
 */
package com.keevo.shared.capability.portal;
