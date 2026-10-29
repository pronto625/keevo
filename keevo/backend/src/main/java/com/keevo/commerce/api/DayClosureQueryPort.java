package com.keevo.commerce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Day-closure reads for other modules (Story 12.2c), in the current tenant. */
public interface DayClosureQueryPort {

    Optional<DayClosureView> findById(UUID closureId);

    /** The store's closures of a (WAT) day — usually 0 or 1. */
    List<DayClosureView> findByStoreAndDate(UUID storeId, LocalDate date);

    /** The store's closure just before {@code before} (the one bounding a closure's window). */
    Optional<DayClosureView> findPreviousForStore(UUID storeId, Instant before);
}
