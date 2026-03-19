package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;

/**
 * ClosureReportStrategy — GoF Strategy pattern for building closure reports.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Implementations:
 * <ul>
 *   <li>ManualReportStrategy — "✅ Clôture manuelle"</li>
 *   <li>AutoReportStrategy — "⏰ Rapport auto-généré (clôture oubliée)"</li>
 * </ul>
 */
public interface ClosureReportStrategy {

    /**
     * Build the WhatsApp report text for a day closure.
     *
     * @param event        DayClosedEvent with closure data
     * @param storeName    Human-readable store name
     * @param employeeName Name of employee who triggered closure (or "Système" for auto)
     * @return Formatted WhatsApp message
     */
    String buildReport(DayClosedEvent event, String storeName, String employeeName);
}
