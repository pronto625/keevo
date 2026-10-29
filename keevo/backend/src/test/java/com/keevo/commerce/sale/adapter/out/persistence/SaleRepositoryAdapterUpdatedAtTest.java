package com.keevo.commerce.sale.adapter.out.persistence;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.impl.SaleRepositoryAdapter;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.event.*;
import com.keevo.commerce.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The pull sends sales changed since the cursor by updated_at: every write must bump it. */
@ExtendWith(MockitoExtension.class)
class SaleRepositoryAdapterUpdatedAtTest {

    @Mock private SaleSpringRepository springRepository;

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    private SaleJpaEntity stored() {
        var entity = new SaleJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setStatus("PENDING_VALIDATION");
        entity.setCreatedAt(LONG_AGO);
        entity.setUpdatedAt(LONG_AGO);
        return entity;
    }

    @Test
    void updateStatus_bumpsUpdatedAt() {
        var entity = stored();
        when(springRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        var before = Instant.now();

        new SaleRepositoryAdapter(springRepository).updateStatus(entity.getId(), SaleStatus.CANCELLED);

        var saved = ArgumentCaptor.forClass(SaleJpaEntity.class);
        verify(springRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getValue().getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void remapItemProductIds_bumpsUpdatedAt() {
        var entity = stored();
        when(springRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        var before = Instant.now();

        new SaleRepositoryAdapter(springRepository).remapItemProductIds(entity.getId(), Map.of());

        verify(springRepository).save(entity);
        assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void save_setsUpdatedAt() {
        var item = new SaleItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "Produit A", 1500, 1500, 2);
        var sale = new Sale(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED, LONG_AGO, LONG_AGO, List.of(item));
        var before = Instant.now();

        new SaleRepositoryAdapter(springRepository).save(sale);

        var saved = ArgumentCaptor.forClass(SaleJpaEntity.class);
        // Story v1s-13-10: flushed so a stale version fails inside the calling service.
        verify(springRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUpdatedAt()).isAfterOrEqualTo(before);
    }

    // Two cancellations racing must not both win: the transition is one conditional UPDATE.
    @Test
    void updateStatusIfCurrent_rowUpdated_returnsTrueAndBumpsUpdatedAt() {
        var id = UUID.randomUUID();
        when(springRepository.updateStatusIfCurrent(eq(id), eq("COMPLETED"), eq("CANCELLED"), any()))
                .thenReturn(1);
        var before = Instant.now();

        boolean changed = new SaleRepositoryAdapter(springRepository)
                .updateStatusIfCurrent(id, SaleStatus.COMPLETED, SaleStatus.CANCELLED);

        assertThat(changed).isTrue();
        var now = ArgumentCaptor.forClass(Instant.class);
        verify(springRepository).updateStatusIfCurrent(eq(id), eq("COMPLETED"), eq("CANCELLED"), now.capture());
        assertThat(now.getValue()).isAfterOrEqualTo(before);
        verify(springRepository, never()).findById(any());
        verify(springRepository, never()).save(any());
    }

    @Test
    void updateStatusIfCurrent_statusAlreadyChanged_returnsFalse() {
        var id = UUID.randomUUID();
        when(springRepository.updateStatusIfCurrent(eq(id), eq("COMPLETED"), eq("CANCELLED"), any()))
                .thenReturn(0);

        assertThat(new SaleRepositoryAdapter(springRepository)
                .updateStatusIfCurrent(id, SaleStatus.COMPLETED, SaleStatus.CANCELLED)).isFalse();
    }

    @Test
    void updateStatusIfCurrent_queryIsConditionalAndBumpsUpdatedAt() throws Exception {
        var method = SaleSpringRepository.class.getMethod("updateStatusIfCurrent",
                UUID.class, String.class, String.class, Instant.class);
        String jpql = method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();

        assertThat(jpql).contains("s.updatedAt = :now");
        assertThat(jpql).contains("s.version = s.version + 1");
        assertThat(jpql).contains("WHERE s.id = :id AND s.status = :expected");
        assertThat(method.isAnnotationPresent(org.springframework.data.jpa.repository.Modifying.class)).isTrue();
    }
}
