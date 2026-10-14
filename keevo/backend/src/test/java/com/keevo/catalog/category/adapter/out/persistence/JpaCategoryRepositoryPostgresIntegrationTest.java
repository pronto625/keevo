package com.keevo.catalog.category.adapter.out.persistence;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category created offline keeps the client UUID (sync CREATE_CATEGORY). Found on device:
 * with {@code @GeneratedValue} and a client UUID, Hibernate 6.6 merged a "detached" row that
 * did not exist and every mobile category create was REJECTED / INTERNAL_ERROR.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(JpaCategoryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("JpaCategoryRepository on PostgreSQL")
class JpaCategoryRepositoryPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired JpaCategoryRepository repository;
    @Autowired CategoryJpaRepository jpaRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(s -> jpaRepository.deleteAllById(created));
        created.clear();
    }

    @Test
    @DisplayName("insert keeps the client UUID; a later save updates the same row")
    void insertWithClientId_thenRename() {
        UUID id = UUID.randomUUID();
        created.add(id);

        Category inserted = tx().execute(s -> repository.insert(
                new Category(id, "Épicerie fine", null, true, true, NOW, NOW)));
        assertThat(inserted.id()).isEqualTo(id);

        tx().executeWithoutResult(s -> {
            Category loaded = repository.findById(id).orElseThrow();
            repository.save(new Category(id, "Épicerie", loaded.parentId(), true, true,
                    loaded.createdAt(), NOW.plusSeconds(1)));
        });

        Category reloaded = tx().execute(s -> repository.findById(id).orElseThrow());
        assertThat(reloaded.name()).isEqualTo("Épicerie");
        assertThat(jpaRepository.count()).isPositive();
    }

    @Test
    @DisplayName("createCustom assigns a UUID itself")
    void createCustom_assignsId() {
        Category c = tx().execute(s -> repository.createCustom("Quincaillerie", null));
        created.add(c.id());
        assertThat(c.id()).isNotNull();
        Optional<Category> found = tx().execute(s -> repository.findById(c.id()));
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("name lookup ignores case and surrounding spaces")
    void findByName_ignoresCase() {
        UUID id = UUID.randomUUID();
        created.add(id);
        tx().executeWithoutResult(s -> repository.insert(
                new Category(id, "Boissons Fraîches", null, true, true, NOW, NOW)));

        Optional<Category> found = tx().execute(s -> repository.findByNameAndParentId(" boissons fraîches ", null));
        assertThat(found).map(Category::id).contains(id);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
