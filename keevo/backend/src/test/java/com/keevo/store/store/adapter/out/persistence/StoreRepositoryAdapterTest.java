package com.keevo.store.store.adapter.out.persistence;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * StoreRepositoryAdapterTest — Unit tests with mocked JpaStoreRepository (Story 3.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreRepositoryAdapter")
class StoreRepositoryAdapterTest {

    @Mock JpaStoreRepository jpa;
    @InjectMocks StoreRepositoryAdapter adapter;

    UUID storeId;
    StoreJpaEntity sampleEntity;
    Store sampleDomain;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        sampleEntity = new StoreJpaEntity();
        sampleEntity.setId(storeId);
        sampleEntity.setName("Test Store");
        sampleEntity.setType(StoreType.STORE);
        sampleEntity.setActive(true);
        sampleEntity.setCreatedAt(Instant.now());
        sampleEntity.setUpdatedAt(Instant.now());

        sampleDomain = new Store(storeId, "Test Store", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("save() should map domain to entity and return domain")
    void save_shouldMapDomainToEntityAndBack() {
        when(jpa.save(any())).thenReturn(sampleEntity);

        Store result = adapter.save(sampleDomain);

        assertThat(result.id()).isEqualTo(storeId);
        assertThat(result.name()).isEqualTo("Test Store");
        verify(jpa).save(any());
    }

    @Test
    @DisplayName("findById() returns empty Optional when not found")
    void findById_returnsOptionalEmpty_whenNotFound() {
        when(jpa.findById(storeId)).thenReturn(Optional.empty());

        assertThat(adapter.findById(storeId)).isEmpty();
    }

    @Test
    @DisplayName("countActive() delegates to JpaStoreRepository")
    void countActive_delegatesToJpaRepository() {
        when(jpa.countByIsActiveTrue()).thenReturn(2L);

        assertThat(adapter.countActive()).isEqualTo(2);
        verify(jpa).countByIsActiveTrue();
    }

    @Test
    @DisplayName("existsWarehouse() returns true when warehouse present")
    void existsWarehouse_returnsTrueWhenWarehousePresent() {
        when(jpa.existsByType(StoreType.WAREHOUSE)).thenReturn(true);

        assertThat(adapter.existsWarehouse()).isTrue();
        verify(jpa).existsByType(StoreType.WAREHOUSE);
    }

    @Test
    @DisplayName("findAllActive() returns only active stores")
    void findAllActive_returnsOnlyActiveStores() {
        when(jpa.findByIsActiveTrue()).thenReturn(List.of(sampleEntity));

        List<Store> result = adapter.findAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isActive()).isTrue();
    }
}
