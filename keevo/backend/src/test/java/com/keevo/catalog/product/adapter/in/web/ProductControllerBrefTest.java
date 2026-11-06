package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateOrRestoreProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.GetProductPricingUseCase;
import com.keevo.catalog.product.application.usecase.LookupProductByBarcodeUseCase;
import com.keevo.catalog.product.application.usecase.MergeProductsUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.crm.api.SupplierQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.store.api.StoreQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Story 20.1 — REST: barcode in / new fields out (additive), lookup and merge endpoints. */
@DisplayName("ProductController — catalogue étendu (20.1)")
class ProductControllerBrefTest {

    private final CreateOrRestoreProductUseCase createOrRestore = mock(CreateOrRestoreProductUseCase.class);
    private final UpdateProductUseCase update = mock(UpdateProductUseCase.class);
    private final ProductRepository repository = mock(ProductRepository.class);
    private final LookupProductByBarcodeUseCase lookup = mock(LookupProductByBarcodeUseCase.class);
    private final MergeProductsUseCase merge = mock(MergeProductsUseCase.class);
    private final UUID actor = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test01");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var controller = new ProductController(createOrRestore, update, mock(ArchiveProductUseCase.class),
                repository, mock(GetProductPricingUseCase.class), mock(SupplierQueryPort.class),
                mock(StoreQueryPort.class), lookup, merge);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static Product product(UUID id, String barcode, UUID mergedInto) {
        return new Product(id, "Savon Lux", null, "KEV-SAVON1", null, 500, 0, 0, 0, mergedInto != null,
                ProductStatus.ACTIVE, 0, true, barcode, true, mergedInto, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("GET /{id} expose barcode, productKey, priceManual, mergedIntoId (ajout)")
    void get_exposes_the_new_fields() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(product(id, "6111234567890", null)));

        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.barcode").value("6111234567890"))
                .andExpect(jsonPath("$.data.productKey").value("bc:6111234567890"))
                .andExpect(jsonPath("$.data.priceManual").value(true))
                .andExpect(jsonPath("$.data.mergedIntoId").doesNotExist())
                .andExpect(jsonPath("$.data.trackStock").value(true))
                .andExpect(jsonPath("$.data.restored").doesNotExist());
    }

    @Test
    @DisplayName("POST avec barcode → transmis à la création")
    void create_passes_the_barcode() throws Exception {
        UUID id = UUID.randomUUID();
        when(createOrRestore.execute(any())).thenReturn(
                new CreateOrRestoreProductUseCase.Outcome(product(id, "REF-001", null), false));

        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Savon Lux\",\"price\":500,\"barcode\":\"REF-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.barcode").value("REF-001"));

        var dto = ArgumentCaptor.forClass(CreateProductUseCase.CreateProductDto.class);
        verify(createOrRestore).execute(dto.capture());
        assertThat(dto.getValue().barcode()).isEqualTo("REF-001");
    }

    @Test
    @DisplayName("POST : un code ≤ 100 caractères une fois nettoyé passe (la longueur est vérifiée par le cas d'usage)")
    void create_with_a_padded_barcode_reaches_the_use_case() throws Exception {
        String padded = "  " + "1".repeat(100) + "  ";
        when(createOrRestore.execute(any())).thenReturn(
                new CreateOrRestoreProductUseCase.Outcome(product(UUID.randomUUID(), "1".repeat(100), null), false));

        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Savon\",\"barcode\":\"" + padded + "\"}"))
                .andExpect(status().isCreated());

        var dto = ArgumentCaptor.forClass(CreateProductUseCase.CreateProductDto.class);
        verify(createOrRestore).execute(dto.capture());
        assertThat(dto.getValue().barcode()).isEqualTo(padded);
    }

    @Test
    @DisplayName("POST avec un code de plus de 100 caractères → 422 (refus du cas d'usage)")
    void create_with_a_too_long_barcode_is_422() throws Exception {
        when(createOrRestore.execute(any())).thenThrow(DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                "La référence ou le code-barres ne doit pas dépasser 100 caractères"));

        mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Savon\",\"barcode\":\"" + "1".repeat(101) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("La référence ou le code-barres ne doit pas dépasser 100 caractères"));
    }

    @Test
    @DisplayName("PATCH sans barcode (corps V1) → barcode null transmis (inchangé)")
    void patch_without_barcode_keeps_it() throws Exception {
        UUID id = UUID.randomUUID();
        when(update.execute(any())).thenReturn(product(id, "6111234567890", null));

        mockMvc.perform(patch("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":700}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.barcode").value("6111234567890"));

        var dto = ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(update).execute(dto.capture());
        assertThat(dto.getValue().barcode()).isNull();
    }

    @Test
    @DisplayName("GET /lookup?barcode= → le produit (la route littérale passe avant /{id})")
    void lookup_returns_the_product() throws Exception {
        UUID id = UUID.randomUUID();
        when(lookup.execute("6111234567890")).thenReturn(product(id, "6111234567890", null));

        mockMvc.perform(get("/api/v1/products/lookup").param("barcode", "6111234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("GET /lookup code inconnu → 404 PRODUCT_NOT_FOUND « Aucun produit avec ce code »")
    void lookup_unknown_is_404() throws Exception {
        when(lookup.execute("000")).thenThrow(
                DomainException.userFacing(ErrorCode.PRODUCT_NOT_FOUND, "Aucun produit avec ce code"));

        mockMvc.perform(get("/api/v1/products/lookup").param("barcode", "000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Aucun produit avec ce code"))
                .andExpect(jsonPath("$.domainCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /{id}/merge → 200 avec la cible")
    void merge_returns_the_target() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(merge.execute(a, b, actor)).thenReturn(product(b, null, null));

        mockMvc.perform(post("/api/v1/products/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(b.toString()));
    }

    @Test
    @DisplayName("POST /{id}/merge refusé → 409 PRODUCT_MERGE_INVALID avec la raison en français")
    void merge_refused_is_409() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(merge.execute(eq(a), eq(b), any())).thenThrow(DomainException.userFacing(
                ErrorCode.PRODUCT_MERGE_INVALID, "Ce produit a encore du stock : mettez-le à zéro avant de le fusionner"));

        mockMvc.perform(post("/api/v1/products/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("PRODUCT_MERGE_INVALID"))
                .andExpect(jsonPath("$.error")
                        .value("Ce produit a encore du stock : mettez-le à zéro avant de le fusionner"));
    }

    @Test
    @DisplayName("POST /{id}/merge sans cible → 422")
    void merge_without_target_is_422() throws Exception {
        mockMvc.perform(post("/api/v1/products/{id}/merge", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(merge);
    }
}
