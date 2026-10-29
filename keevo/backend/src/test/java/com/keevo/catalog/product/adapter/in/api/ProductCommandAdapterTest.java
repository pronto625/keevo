package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.CreateDraftProductInput;
import com.keevo.catalog.api.CreateProductInput;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.RegisterProductInput;
import com.keevo.catalog.api.UpdateProductInput;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2a — the catalog product command port maps API inputs onto the existing use cases. */
@ExtendWith(MockitoExtension.class)
class ProductCommandAdapterTest {

    @Mock private CreateProductUseCase createProduct;
    @Mock private UpdateProductUseCase updateProduct;
    @Mock private ArchiveProductUseCase archiveProduct;
    @Mock private UnarchiveProductUseCase unarchiveProduct;
    @Mock private CreateDraftProductUseCase createDraftProduct;
    @Mock private ProductRepository productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProductCommandAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new ProductCommandAdapter(createProduct, updateProduct, archiveProduct,
                unarchiveProduct, createDraftProduct, productRepository, eventPublisher);
    }

    private static Product product(ProductStatus status, int threshold) {
        return new Product(ID, "Sac", null, "KEV-SAC001", CATEGORY,
                4000, 2500, 0, 3, false, status, threshold, Instant.now(), Instant.now());
    }

    @Test
    void create_mapsInputOntoCreateProductDto_withClientIdAndNoThreshold() {
        when(createProduct.execute(any())).thenReturn(product(ProductStatus.ACTIVE, 0));

        var view = adapter.create(new CreateProductInput(ID, "Sac", "cuir", "KEV-SAC001", CATEGORY,
                4000, 2500, 100, 3, ACTOR));

        var captor = ArgumentCaptor.forClass(CreateProductUseCase.CreateProductDto.class);
        verify(createProduct).execute(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CreateProductUseCase.CreateProductDto(
                "Sac", "cuir", "KEV-SAC001", CATEGORY, 4000, 2500, 100, 3,
                ACTOR, 0, null, null, null, null, ID));
        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.status()).isEqualTo(ProductState.ACTIVE);
    }

    @Test
    void update_mapsInputOntoUpdateProductDto_keepingTheActorRole() {
        when(updateProduct.execute(any())).thenReturn(product(ProductStatus.ACTIVE, 0));

        adapter.update(new UpdateProductInput(ID, "Sac", null, null, CATEGORY, 4500, null, null, null,
                ACTOR, "EMPLOYEE"));

        verify(updateProduct).execute(new UpdateProductUseCase.UpdateProductDto(
                ID, "Sac", null, null, CATEGORY, 4500, null, null, null, ACTOR, "EMPLOYEE"));
    }

    @Test
    void archive_and_unarchive_delegate() {
        adapter.archive(ID, ACTOR);
        adapter.unarchive(ID, ACTOR);

        verify(archiveProduct).execute(new ArchiveProductUseCase.ArchiveProductDto(ID, ACTOR));
        verify(unarchiveProduct).execute(new UnarchiveProductUseCase.UnarchiveProductDto(ID, ACTOR));
    }

    @Test
    void createDraft_mapsInputOntoCreateDraftCommand() {
        when(createDraftProduct.execute(any())).thenReturn(product(ProductStatus.DRAFT, 0));

        var view = adapter.createDraft(new CreateDraftProductInput(ID, "Sac", null, CATEGORY, 4000,
                null, null, ACTOR, "EMPLOYEE", "Sync"));

        verify(createDraftProduct).execute(new CreateDraftProductUseCase.CreateDraftCommand(
                ID, "Sac", null, CATEGORY, 4000, null, null, ACTOR, "EMPLOYEE", "Sync"));
        assertThat(view.status()).isEqualTo(ProductState.DRAFT);
    }

    @Test
    void register_persistsAnActiveProductWithoutStockNorThreshold() {
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant now = Instant.now();

        var view = adapter.register(new RegisterProductInput(ID, "Sac", "KEV-SAC001", CATEGORY, 4000, now,
                ACTOR, null, null, null));

        var captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        Product saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(ID);
        assertThat(saved.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(saved.getPriceValue()).isEqualTo(4000);
        assertThat(saved.getBuyPriceValue()).isZero();
        assertThat(saved.getStockQuantity()).isZero();
        assertThat(saved.getMinimumThreshold()).isZero();
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(view.sku()).isEqualTo("KEV-SAC001");
    }

    @Test
    void changeMinimumThreshold_savesTheCopyWithTheNewThreshold() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product(ProductStatus.ACTIVE, 2)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = adapter.changeMinimumThreshold(ID, 9);

        assertThat(view.minimumThreshold()).isEqualTo(9);
        verify(productRepository).save(argThat(p -> p.getMinimumThreshold() == 9 && ID.equals(p.getId())));
    }

    @Test
    void changeMinimumThreshold_unknownProduct_isProductNotFound() {
        when(productRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.changeMinimumThreshold(ID, 9))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        verify(productRepository, never()).save(any());
    }

    // 12.2b: the catalog publishes its own ProductCreatedEvent on register (payload unchanged).
    @Test
    void register_publishesProductCreatedEventWithTheCallerActorContext() {
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant now = Instant.now();
        TenantContext.setCurrentTenant("kv_test");
        try {
            adapter.register(new RegisterProductInput(ID, "Sac", "KEV-SAC001", CATEGORY, 4000, now,
                    ACTOR, "EMPLOYEE", "Awa", "Boutique Akwa"));
        } finally {
            TenantContext.clear();
        }

        verify(eventPublisher).publishEvent(new ProductCreatedEvent(
                ID, "Sac", "KEV-SAC001", "kv_test", ACTOR, "EMPLOYEE", "Awa", "Boutique Akwa", now));
    }
}
