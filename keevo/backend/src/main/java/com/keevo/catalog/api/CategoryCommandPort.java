package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Write access to the catalog's categories for other modules. Each operation keeps the audit
 * events and transactional behaviour of the corresponding catalog use case.
 *
 * <p>Story 12.6a: onboarding template seeding is no longer a call — the catalog seeds on
 * {@code TenantOnboardedEvent} ({@code TenantOnboardedCategorySeeder}).
 */
public interface CategoryCommandPort {

    /** Creates a category with a caller-assigned id (offline sync). */
    CategoryView createWithId(String tenantSchema, UUID categoryId, String name, UUID parentId, UUID actorId);

    /** Flips the active flag. @throws com.keevo.shared.domain.exception.DomainException CATEGORY_NOT_FOUND */
    CategoryView toggleActive(String tenantSchema, UUID categoryId, UUID actorId);

    CategoryView rename(String tenantSchema, UUID categoryId, String name, UUID actorId);

}
