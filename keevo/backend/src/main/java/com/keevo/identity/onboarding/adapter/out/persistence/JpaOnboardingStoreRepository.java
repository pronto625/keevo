package com.keevo.identity.onboarding.adapter.out.persistence;

import com.keevo.identity.onboarding.domain.port.out.OnboardingStoreRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JpaOnboardingStoreRepository — Persistence adapter for OnboardingStoreRepository port.
 *
 * <p>Uses {@link EntityManager#createNativeQuery} to execute raw SQL on the
 * <em>Hibernate-managed</em> connection, which is obtained via
 * {@code SchemaAwareMultiTenantConnectionProvider} and already has
 * {@code SET search_path TO "kv_xxx"} applied.
 *
 * <h3>Why not JdbcTemplate?</h3>
 * <p>{@code JpaTransactionManager} binds the Hibernate session to the
 * {@code EntityManagerFactory} key in {@code TransactionSynchronizationManager},
 * NOT to the {@code DataSource} key. Therefore {@code DataSourceUtils.getConnection()}
 * inside {@code JdbcTemplate} finds no bound connection and falls back to
 * {@code dataSource.getConnection()} — returning a fresh HikariCP connection
 * without {@code search_path} set → {@code relation "stores" does not exist}.
 *
 * <p>{@code EntityManager.createNativeQuery()} runs on the Hibernate session's own
 * connection, which is already schema-routed. This is the only reliable path
 * for raw SQL inside a JPA multi-tenant setup with {@code JpaTransactionManager}.
 */
@Component
public class JpaOnboardingStoreRepository implements OnboardingStoreRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaOnboardingStoreRepository.class);

    private static final String SQL_UPDATE_STORE_NAME =
        "UPDATE stores SET name = :name, updated_at = NOW() " +
        "WHERE id = (SELECT id FROM stores ORDER BY created_at ASC LIMIT 1)";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void updateStoreName(String newName) {
        String schemaName = TenantContext.getCurrentTenant();
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalStateException("TenantContext not set — cannot route store update");
        }
        int updated = entityManager.createNativeQuery(SQL_UPDATE_STORE_NAME)
                .setParameter("name", newName)
                .executeUpdate();
        log.debug("Updated store name to '{}' in schema '{}' — {} row(s) affected",
                newName, schemaName, updated);
    }
}
