package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Story 12.2a — the catalog product query port delegates to the repository and maps faithfully. */
@ExtendWith(MockitoExtension.class)
class ProductQueryAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ProductRepository productRepository;

    private ProductQueryAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-01T08:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ProductQueryAdapter(productRepository);
    }

    private static Product product(boolean archived, ProductStatus status) {
        return new Product(ID, "Robe Wax", "coton", "KEV-ROBE01", CATEGORY,
                5000, 3000, 200, 7, archived, status, 4, CREATED, UPDATED);
    }

    @Test
    void findById_mapsEveryField() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product(false, ProductStatus.DRAFT)));

        assertThat(adapter.findById(TenantContext.getCurrentTenant(), ID)).contains(new ProductView(ID, "Robe Wax", "coton", "KEV-ROBE01",
                CATEGORY, 5000, 3000, 200, 7, false, ProductState.DRAFT, 4, CREATED, UPDATED));
    }

    @Test
    void findById_unknown_isEmpty() {
        when(productRepository.findById(ID)).thenReturn(Optional.empty());

        assertThat(adapter.findById(TenantContext.getCurrentTenant(), ID)).isEmpty();
    }

    @Test
    void findByName_and_existsByName_delegate() {
        when(productRepository.findByName("robe wax")).thenReturn(Optional.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.existsByName("Robe Wax")).thenReturn(true);

        assertThat(adapter.findByName(TenantContext.getCurrentTenant(), "robe wax")).map(ProductView::id).contains(ID);
        assertThat(adapter.existsByName(TenantContext.getCurrentTenant(), "Robe Wax")).isTrue();
    }

    @Test
    void existsBySku_isTrueOnlyWhenTheSkuIsTaken() {
        when(productRepository.findBySku("KEV-ROBE01")).thenReturn(Optional.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.findBySku("KEV-FREE01")).thenReturn(Optional.empty());

        assertThat(adapter.existsBySku(TenantContext.getCurrentTenant(), "KEV-ROBE01")).isTrue();
        assertThat(adapter.existsBySku(TenantContext.getCurrentTenant(), "KEV-FREE01")).isFalse();
    }

    @Test
    void listQueries_mapEachProduct() {
        when(productRepository.findAllActive()).thenReturn(List.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.findAllByIds(List.of(ID))).thenReturn(List.of(product(true, ProductStatus.ACTIVE)));
        when(productRepository.findByCategoryIds(List.of(CATEGORY))).thenReturn(List.of(product(false, ProductStatus.ACTIVE)));

        assertThat(adapter.findAllActive(TenantContext.getCurrentTenant())).extracting(ProductView::id).containsExactly(ID);
        assertThat(adapter.findAllByIds(TenantContext.getCurrentTenant(), List.of(ID))).extracting(ProductView::archived).containsExactly(true);
        assertThat(adapter.findByCategoryIds(TenantContext.getCurrentTenant(), List.of(CATEGORY))).extracting(ProductView::categoryId).containsExactly(CATEGORY);
    }

    @Test
    void findPhotoUrlsByIds_delegates() {
        when(productRepository.findPhotoUrlsByIds(List.of(ID))).thenReturn(Map.of(ID, "/photos/a.jpg"));

        assertThat(adapter.findPhotoUrlsByIds(TenantContext.getCurrentTenant(), List.of(ID))).containsEntry(ID, "/photos/a.jpg");
    }

    @Test
    void findStatusName_readsTheProductStatusThroughTheDomainRepository() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product(false, ProductStatus.DRAFT)));

        assertThat(adapter.findStatusName(TenantContext.getCurrentTenant(), ID)).contains("DRAFT");
    }

    @Test
    void findStatusName_unknownProduct_isEmpty() {
        when(productRepository.findById(ID)).thenReturn(Optional.empty());

        assertThat(adapter.findStatusName(TenantContext.getCurrentTenant(), ID)).isEmpty();
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findById("kv_other1", ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findById(null, ID))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(productRepository);
    }
}
