package com.keevo.commerce.sale.adapter.out.persistence.jpa;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SaleSpringRepository extends JpaRepository<SaleJpaEntity, UUID> {
}
