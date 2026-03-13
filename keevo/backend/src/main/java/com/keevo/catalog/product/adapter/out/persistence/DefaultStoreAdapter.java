package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.domain.port.out.DefaultStorePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * DefaultStoreAdapter — retrieves the default store UUID for the current tenant.
 *
 * <p>Uses {@link EntityManager#createNativeQuery} (same pattern as
 * {@link com.keevo.identity.onboarding.adapter.out.persistence.JpaOnboardingStoreRepository})
 * to stay on the Hibernate-managed connection that already has the correct
 * {@code search_path} set for multi-tenant routing.
 *
 * <p>Story 2.4 — batch CSV import uses the first/default store for stock movements.
 */
@Component
public class DefaultStoreAdapter implements DefaultStorePort {

    private static final String SQL_FIRST_STORE_ID =
            "SELECT id::text FROM stores WHERE is_active = TRUE ORDER BY created_at ASC LIMIT 1";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public UUID getDefaultStoreId() {
        Object result;
        try {
            result = entityManager.createNativeQuery(SQL_FIRST_STORE_ID)
                    .getSingleResult();
        } catch (Exception e) {
            throw new DomainException(ErrorCode.STORE_NOT_FOUND,
                    "No active store found for this tenant");
        }
        if (result == null) {
            throw new DomainException(ErrorCode.STORE_NOT_FOUND,
                    "No active store found for this tenant");
        }
        return UUID.fromString(result.toString());
    }
}
