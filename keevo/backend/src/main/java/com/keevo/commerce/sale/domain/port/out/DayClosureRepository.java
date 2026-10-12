package com.keevo.commerce.sale.domain.port.out;

import com.keevo.commerce.sale.domain.model.DayClosure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DayClosureRepository — Port out for persisting and querying day closures.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public interface DayClosureRepository {

    /**
     * Save a day closure.
     */
    void save(DayClosure closure);

    /**
     * Check if a closure already exists for the given store and date.
     *
     * @param storeId store UUID
     * @param date    local date (closure day, not timestamp)
     * @return true if closure exists
     */
    boolean existsByStoreIdAndDate(UUID storeId, LocalDate date);

    /** True when a closure with this id exists (client-supplied ids: a retried push). */
    boolean existsById(UUID closureId);

    /**
     * Find all closures for a store on a given date.
     *
     * @param storeId store UUID
     * @param date    local date
     * @return list of closures (usually 0 or 1)
     */
    List<DayClosure> findByStoreIdAndDate(UUID storeId, LocalDate date);

    /**
     * Find the most recent closure for a store (across all dates).
     * Used to compute the sliding window start for the next closure.
     *
     * @param storeId store UUID
     * @return the most recent DayClosure, or empty if none exists
     */
    Optional<DayClosure> findLastClosureForStore(UUID storeId);

    /** One closure by id (Story v1s-18-3 — « Fin de journée » PDF). */
    Optional<DayClosure> findById(UUID closureId);

    /**
     * The store's closure just before {@code before} (greatest {@code closed_at} strictly
     * earlier), i.e. the previous closure that bounds a closure's window (Story v1s-18-3).
     */
    Optional<DayClosure> findPreviousForStore(UUID storeId, Instant before);
}
