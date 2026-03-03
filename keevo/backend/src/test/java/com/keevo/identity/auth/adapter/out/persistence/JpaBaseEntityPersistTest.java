package com.keevo.identity.auth.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * JpaBaseEntityPersistTest — Integration test for JpaBaseEntity UUID pre-assignment.
 *
 * <p>TDD RED: Before implementing Persistable<UUID>, saving a JPA entity with a
 * domain-assigned UUID (non-null ID before persist) throws:
 * ObjectOptimisticLockingFailureException: unsaved-value mapping was incorrect
 *
 * <p>Root cause: SimpleJpaRepository.save() calls isNew() which returns false
 * when ID != null, so it calls merge() instead of persist(). Hibernate merge()
 * tries to load the entity from DB, finds nothing, then fails the optimistic lock.
 *
 * <p>GREEN: JpaBaseEntity implements Persistable<UUID> with @Transient isNew flag,
 * forcing persist() for entities with pre-assigned domain UUIDs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5444/keevo_dev",
        "spring.datasource.username=keevo",
        "spring.datasource.password=keevo_local_pwd",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName("JpaBaseEntity — Persistable<UUID> with domain-assigned IDs")
class JpaBaseEntityPersistTest {

    @Autowired
    private TenantJpaRepository tenantJpaRepository;

    @Test
    @DisplayName("save() with domain-assigned UUID uses persist() not merge() — no OptimisticLockingFailure")
    void save_withPreAssignedUuid_persistsNewEntity() {
        // arrange — simulate what JpaTenantRepository.toJpaEntity() does:
        // the domain Tenant has a UUID from its constructor, passed to the JPA entity
        UUID domainId = UUID.randomUUID();
        TenantJpaEntity entity = new TenantJpaEntity(
                domainId, "KV-TST001", "kv_tst001", "ACTIVE", "FREE", 3, 500, 5);

        // act + assert — FAILS before fix with ObjectOptimisticLockingFailureException
        assertThatNoException()
                .as("Saving a new entity with a pre-assigned domain UUID must not throw")
                .isThrownBy(() -> tenantJpaRepository.save(entity));

        // verify it was actually persisted
        Optional<TenantJpaEntity> found = tenantJpaRepository.findById(domainId);
        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("KV-TST001");
    }

    @Test
    @DisplayName("save() with a second distinct UUID also persists correctly (idempotency check)")
    void save_twoDistinctUuids_bothPersist() {
        // arrange — two separate entities with domain-assigned UUIDs
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        TenantJpaEntity e1 = new TenantJpaEntity(id1, "KV-TST003", "kv_tst003", "ACTIVE", "FREE", 3, 500, 5);
        TenantJpaEntity e2 = new TenantJpaEntity(id2, "KV-TST004", "kv_tst004", "ACTIVE", "FREE", 3, 500, 5);

        // act
        tenantJpaRepository.save(e1);
        tenantJpaRepository.save(e2);

        // assert
        assertThat(tenantJpaRepository.findById(id1)).isPresent();
        assertThat(tenantJpaRepository.findById(id2)).isPresent();
    }
}
