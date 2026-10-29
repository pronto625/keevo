package com.keevo.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * Write access to the catalog's categories for other modules. Each operation keeps the audit
 * events and transactional behaviour of the corresponding catalog use case.
 */
public interface CategoryCommandPort {

    /** Creates a category with a caller-assigned id (offline sync). */
    CategoryView createWithId(String tenantSchema, UUID categoryId, String name, UUID parentId, UUID actorId);

    /** Flips the active flag. @throws com.keevo.shared.domain.exception.DomainException CATEGORY_NOT_FOUND */
    CategoryView toggleActive(String tenantSchema, UUID categoryId, UUID actorId);

    CategoryView rename(String tenantSchema, UUID categoryId, String name, UUID actorId);

    /**
     * Seeds the given root categories (active, non-custom) — onboarding sector template.
     * Null or blank names are skipped.
     *
     * <p>Same behaviour as the pre-12.2a onboarding code: it emits <b>no</b>
     * {@code CategoryCreatedEvent} and is <b>not idempotent</b> — calling it twice creates the
     * categories twice; the caller (onboarding) runs it once per tenant.
     *
     * @return the number of categories created
     */
    int seedTemplateCategories(String tenantSchema, List<String> names);
}
