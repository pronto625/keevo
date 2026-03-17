package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleItemJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import org.springframework.stereotype.Component;

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
        return springRepository.findById(saleId).map(this::toDomain);
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
}
