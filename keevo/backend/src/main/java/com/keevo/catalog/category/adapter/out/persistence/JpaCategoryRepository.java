package com.keevo.catalog.category.adapter.out.persistence;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JpaCategoryRepository — Persistence adapter implementing the CategoryRepository port.
 *
 * <p>Architecture: Secondary adapter (driven side) in hexagonal architecture.
 * Maps between domain {@link Category} records and JPA {@link CategoryJpaEntity} objects.
 *
 * <p>Multi-tenant: {@link com.keevo.shared.infrastructure.persistence.SchemaAwareMultiTenantConnectionProvider} automatically sets the
 * correct PostgreSQL {@code search_path} on every Hibernate connection, so no manual
 * schema routing is required here.
 */
@Component
public class JpaCategoryRepository implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;

    public JpaCategoryRepository(CategoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // ── Implemented: used during onboarding ────────────────────────────────────

    @Override
    public List<Category> saveAll(List<Category> categories) {
        List<CategoryJpaEntity> entities = categories.stream()
            .map(this::toEntity)
            .collect(Collectors.toList());
        return jpaRepository.saveAll(entities).stream()
            .map(this::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public Category save(Category category) {
        return toDomain(jpaRepository.save(toEntity(category)));
    }

    @Override
    public Optional<Category> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Category> findAllActive() {
        return jpaRepository.findAllActive().stream()
            .map(this::toDomain)
            .collect(Collectors.toList());
    }

    // ── Implemented: Story 2.1 ─────────────────────────────────────────────────

    @Override
    public List<Category> findByParentId(UUID parentId) {
        return jpaRepository.findByParentId(parentId).stream()
            .map(this::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public Category toggleActive(UUID id) {
        // Load entity, flip isActive, save, return domain object
        CategoryJpaEntity entity = jpaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
        
        entity.setActive(!entity.isActive());
        entity.setUpdatedAt(Instant.now());
        
        CategoryJpaEntity savedEntity = jpaRepository.save(entity);
        
        return toDomain(savedEntity);
    }

    @Override
    public Category createCustom(String name, UUID parentId) {
        // Create with isCustom=true, parentId param
        var entity = new CategoryJpaEntity(
            name,
            parentId,
            true, // isActive = true by default
            true, // isCustom = true for merchant-created categories
            Instant.now(),
            Instant.now()
        );
        
        CategoryJpaEntity savedEntity = jpaRepository.save(entity);
        
        return toDomain(savedEntity);
    }

    @Override
    public Category rename(UUID id, String name) {
        CategoryJpaEntity entity = jpaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
        entity.setName(name);
        entity.setUpdatedAt(Instant.now());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Category> findByNameAndParentId(String name, UUID parentId) {
        return jpaRepository.findByNameAndParentId(name, parentId).map(this::toDomain);
    }

    @Override
    public void deactivate(UUID id) {
        CategoryJpaEntity entity = jpaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        jpaRepository.save(entity);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private CategoryJpaEntity toEntity(Category domain) {
        if (domain.id() == null) {
            // New entity — let Hibernate generate the UUID via @GeneratedValue
            return new CategoryJpaEntity(
                domain.name(),
                domain.parentId(),
                domain.isActive(),
                domain.isCustom(),
                domain.createdAt(),
                domain.updatedAt()
            );
        }
        return new CategoryJpaEntity(
            domain.id(),
            domain.name(),
            domain.parentId(),
            domain.isActive(),
            domain.isCustom(),
            domain.createdAt(),
            domain.updatedAt()
        );
    }

    private Category toDomain(CategoryJpaEntity entity) {
        return new Category(
            entity.getId(),
            entity.getName(),
            entity.getParentId(),
            entity.isActive(),
            entity.isCustom(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}