package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleItemJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class SaleRepositoryAdapter implements SaleRepository {

    private final SaleSpringRepository springRepository;

    public SaleRepositoryAdapter(SaleSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public void save(Sale sale) {
        SaleJpaEntity entity = toJpaEntity(sale);
        springRepository.save(entity);
    }

    @Override
    public boolean existsById(UUID saleId) {
        return springRepository.existsById(saleId);
    }

    @Override
    public Optional<Sale> findById(UUID saleId) {
        return springRepository.findByIdWithItems(saleId).map(this::toDomain);
    }

    // ── Mapping ───────────────────────────────────────────────

    private SaleJpaEntity toJpaEntity(Sale sale) {
        var entity = new SaleJpaEntity();
        entity.setId(sale.getId());
        entity.setStoreId(sale.getStoreId());
        entity.setEmployeeId(sale.getEmployeeId());
        entity.setClientId(sale.getClientId());
        entity.setTotalAmount(sale.getTotalAmount());
        entity.setDiscountAmount(sale.getDiscountAmount());
        entity.setPaymentMode(sale.getPaymentMode().name());
        entity.setStatus(sale.getStatus().name());
        entity.setOccurredAt(sale.getOccurredAt());
        entity.setCreatedAt(sale.getCreatedAt());

        var itemEntities = sale.getItems().stream()
                .map(item -> toItemJpaEntity(item, entity))
                .toList();
        entity.setItems(new java.util.ArrayList<>(itemEntities));
        return entity;
    }

    private SaleItemJpaEntity toItemJpaEntity(SaleItem item, SaleJpaEntity saleEntity) {
        var entity = new SaleItemJpaEntity();
        entity.setId(item.getId());
        entity.setSale(saleEntity);
        entity.setProductId(item.getProductId());
        entity.setVariantId(item.getVariantId());
        entity.setProductName(item.getProductName());
        entity.setCatalogueUnitPrice(item.getCatalogueUnitPrice());
        entity.setAppliedUnitPrice(item.getAppliedUnitPrice());
        entity.setQuantity(item.getQuantity());
        entity.setSubtotal(item.getSubtotal());
        entity.setCreatedAt(saleEntity.getCreatedAt());
        return entity;
    }

    private Sale toDomain(SaleJpaEntity entity) {
        var items = entity.getItems().stream()
                .map(this::toItemDomain)
                .toList();
        return new Sale(
                entity.getId(),
                entity.getStoreId(),
                entity.getEmployeeId(),
                entity.getClientId(),
                PaymentMode.valueOf(entity.getPaymentMode()),
                entity.getTotalAmount(),
                entity.getDiscountAmount(),
                SaleStatus.valueOf(entity.getStatus()),
                entity.getOccurredAt(),
                entity.getCreatedAt(),
                items
        );
    }

    private SaleItem toItemDomain(SaleItemJpaEntity entity) {
        return new SaleItem(
                entity.getId(),
                entity.getSale().getId(),
                entity.getProductId(),
                entity.getVariantId(),
                entity.getProductName(),
                entity.getCatalogueUnitPrice(),
                entity.getAppliedUnitPrice(),
                entity.getQuantity()
        );
    }

    @Override
    public List<Sale> findPendingByProductId(UUID productId) {
        return springRepository.findPendingByProductId(productId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Sale> findByStatus(SaleStatus status) {
        return springRepository.findByStatus(status.name()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void updateStatus(UUID saleId, SaleStatus newStatus) {
        springRepository.findById(saleId).ifPresent(entity -> {
            entity.setStatus(newStatus.name());
            springRepository.save(entity);
        });
    }

    @Override
    public void remapItemProductIds(UUID saleId, Map<UUID, UUID> remappings) {
        springRepository.findById(saleId).ifPresent(entity -> {
            for (SaleItemJpaEntity item : entity.getItems()) {
                UUID newId = remappings.get(item.getProductId());
                if (newId != null) {
                    item.setProductId(newId);
                }
            }
            springRepository.save(entity);
        });
    }

    // ── Story 4.4 — Sales History queries ─────────────────────────────────────

    @Override
    public Page<Sale> findByStoreIdAndEmployeeIdAndDateRange(UUID storeId, UUID employeeId,
                                                              Instant from, Instant to, Pageable pageable) {
        return springRepository.findByStoreIdAndEmployeeIdAndOccurredAtBetween(
                storeId, employeeId, from, to, pageable
        ).map(this::toDomain);
    }

    @Override
    public Page<Sale> findByStoreIdAndDateRange(UUID storeId, Instant from, Instant to, Pageable pageable) {
        return springRepository.findByStoreIdAndOccurredAtBetween(storeId, from, to, pageable)
                .map(this::toDomain);
    }

    @Override
    public Page<Sale> findByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                          SaleStatus status, Pageable pageable) {
        return springRepository.findByStoreIdAndOccurredAtBetweenAndStatus(
                storeId, from, to, status.name(), pageable
        ).map(this::toDomain);
    }

    @Override
    public boolean existsByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                         SaleStatus status) {
        return springRepository.existsByStoreIdAndOccurredAtBetweenAndStatus(
                storeId, from, to, status.name()
        );
    }
}
