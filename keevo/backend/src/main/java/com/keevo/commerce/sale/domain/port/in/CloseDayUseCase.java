package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.DayClosure;

import java.time.Instant;
import java.util.UUID;

/**
 * CloseDayUseCase — Port for closing a store's day and computing summary.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Triggered manually by employee/owner or automatically by scheduler at 20h00.
 */
public interface CloseDayUseCase {

    /**
     * Command to close a day for a store.
     *
     * @param storeId     Store to close
     * @param actorId     Employee/Owner triggering closure, or SYSTEM_UUID for automatic
     * @param tenantId    Tenant schema name
     * @param isAutomatic true if triggered by scheduler
     * @param closureId   client-generated id (offline device) — null → server generates
     * @param closedAt    when the closure was made on the device — null → now; it decides
     *                    which day is closed, so an offline closure pushed the next morning
     *                    still closes the day it was made
     */
    record CloseDayCommand(UUID storeId, UUID actorId, String tenantId, boolean isAutomatic,
                           UUID closureId, Instant closedAt) {
        public CloseDayCommand(UUID storeId, UUID actorId, String tenantId, boolean isAutomatic) {
            this(storeId, actorId, tenantId, isAutomatic, null, null);
        }
    }

    /**
     * Close the day for the given store.
     *
     * @param command closure command
     * @return DayClosure aggregate containing id, summary and metadata
     * @throws com.keevo.shared.domain.exception.DomainException with DAY_ALREADY_CLOSED if already closed
     */
    DayClosure closeDay(CloseDayCommand command);
}
