package com.keevo.inventory.counting.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class InventorySessionRepositoryAdapter implements InventorySessionRepository {

    private final JpaInventorySessionRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public InventorySessionRepositoryAdapter(JpaInventorySessionRepository jpaRepository,
                                              ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public InventorySession save(InventorySession session) {
        InventorySessionJpaEntity entity = toJpa(session);
        InventorySessionJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<InventorySession> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<InventorySession> findActiveByStoreId(UUID storeId) {
        return jpaRepository.findByStoreIdAndStatus(storeId, InventorySessionStatus.IN_PROGRESS)
                .map(this::toDomain);
    }

    @Override
    public List<InventorySession> findByStoreId(UUID storeId, int page, int size) {
        return jpaRepository.findByStoreIdOrderByStartedAtDesc(storeId, PageRequest.of(page, size))
                .getContent().stream().map(this::toDomain).toList();
    }

    @Override
    public List<InventorySession> findAll(int page, int size) {
        return jpaRepository.findAllByOrderByStartedAtDesc(PageRequest.of(page, size))
                .getContent().stream().map(this::toDomain).toList();
    }

    // ── Mapping ───────────────────────────────────────────────────

    InventorySession toDomain(InventorySessionJpaEntity entity) {
        List<UUID> categoryIds = deserializeCategoryIds(entity.getCategoryIds());
        return new InventorySession(
                entity.getId(),
                entity.getStoreId(),
                entity.getScope(),
                categoryIds,
                entity.getStatus(),
                entity.getStartedBy(),
                entity.getStartedAt(),
                entity.getCancelledBy(),
                entity.getCancelledAt(),
                entity.getCompletedAt(),
                entity.getUpdatedAt()
        );
    }

    InventorySessionJpaEntity toJpa(InventorySession session) {
        InventorySessionJpaEntity entity = new InventorySessionJpaEntity();
        entity.setId(session.getId());
        entity.setStoreId(session.getStoreId());
        entity.setScope(session.getScope());
        entity.setCategoryIds(serializeCategoryIds(session.getCategoryIds()));
        entity.setStatus(session.getStatus());
        entity.setStartedBy(session.getStartedBy());
        entity.setStartedAt(session.getStartedAt());
        entity.setCancelledBy(session.getCancelledBy());
        entity.setCancelledAt(session.getCancelledAt());
        entity.setCompletedAt(session.getCompletedAt());
        entity.setUpdatedAt(session.getUpdatedAt());
        return entity;
    }

    private String serializeCategoryIds(List<UUID> categoryIds) {
        if (categoryIds == null) return null;
        try {
            return objectMapper.writeValueAsString(categoryIds);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize categoryIds", e);
        }
    }

    private List<UUID> deserializeCategoryIds(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize categoryIds", e);
        }
    }
}
