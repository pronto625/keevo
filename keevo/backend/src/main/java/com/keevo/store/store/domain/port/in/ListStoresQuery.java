package com.keevo.store.store.domain.port.in;

/**
 * ListStoresQuery — Input DTO for ListStoresUseCase.
 * Story 3.1 — AC6.
 */
public record ListStoresQuery(boolean includeInactive) {}
