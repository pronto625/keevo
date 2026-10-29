package com.keevo.catalog.product.adapter.in.web;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.GetProductPricingUseCase;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.service.MarginThreshold;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Unit Test for ProductController REST endpoints
 * 
 * Tests controller layer without full Spring context - pure unit test approach
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock
    private CreateProductUseCase createProductUseCase;

    @Mock
    private UpdateProductUseCase updateProductUseCase;

    @Mock
    private ArchiveProductUseCase archiveProductUseCase;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private GetProductPricingUseCase getProductPricingUseCase;

    @Mock
    private com.keevo.store.api.StoreQueryPort storeQuery;

    @Mock
    private com.keevo.crm.api.SupplierQueryPort supplierQueryPort;

    @InjectMocks
    private ProductController productController;

    private MockMvc mockMvc;
    private Product sampleProduct;

    @AfterEach
    void tearDown() {
        // v1s-12-10: do not leak the (OWNER/EMPLOYEE) authentication into other test classes.
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController).build();
        
        // Set up SecurityContext with mock authentication for actorId extraction
        UUID actorId = UUID.randomUUID();
        org.springframework.security.core.Authentication auth =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                actorId, null, java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(auth);
        
        sampleProduct = new Product(
            UUID.randomUUID(),
            "Test Product",
            "Test Description",
            "KEV-TST123",
            UUID.randomUUID(),
            null, // price
            null, // buyPrice
            null, // transportCost
            null, // stockQuantity
            false,
            ProductStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    @DisplayName("POST /api/v1/products should create product with valid data")
    void should_create_product_with_valid_data() throws Exception {
        // Given
        when(createProductUseCase.execute(any())).thenReturn(sampleProduct);

        String requestBody = """
            {
                "name": "Test Product",
                "description": "Test Description",
                "sku": "KEV-TST123",
                "categoryId": "%s"
            }
            """.formatted(UUID.randomUUID());

        // When/Then
        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Test Product"))
                .andExpect(jsonPath("$.data.sku").value("KEV-TST123"));

        verify(createProductUseCase).execute(any());
    }

    // Unit test with MockMvc standalone doesn't activate @Valid validation
    // This would be tested in integration tests with full Spring context
    /*
    @Test
    @DisplayName("POST /api/v1/products should return 400 for invalid data")
    void should_return_400_for_invalid_data() throws Exception {
        // Given - invalid request (missing name)
        String requestBody = """
            {
                "description": "Test Description",
                "categoryId": "%s"
            }
            """.formatted(UUID.randomUUID());

        // When/Then
        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(createProductUseCase);
    }
    */

    @Test
    @DisplayName("GET /api/v1/products should list all active products")
    @WithMockUser
    void should_list_all_active_products() throws Exception {
        // Given
        when(productRepository.findAllActive()).thenReturn(List.of(sampleProduct));

        // When/Then
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("Test Product"));

        verify(productRepository).findAllActive();
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} should return product details")
    @WithMockUser
    void should_return_product_details() throws Exception {
        // Given
        UUID productId = sampleProduct.getId();
        when(productRepository.findById(productId)).thenReturn(Optional.of(sampleProduct));

        // When/Then
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Test Product"))
                .andExpect(jsonPath("$.data.id").value(productId.toString()));

        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} should return 404 when product not found")
    @WithMockUser
    void should_return_404_when_product_not_found() throws Exception {
        // Given
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When/Then
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("PATCH /api/v1/products/{id} should update product")
    @WithMockUser
    void should_update_product() throws Exception {
        // Given
        UUID productId = sampleProduct.getId();
        when(updateProductUseCase.execute(any())).thenReturn(sampleProduct);

        String requestBody = """
            {
                "name": "Updated Product",
                "description": "Updated Description",
                "sku": "KEV-UPD123",
                "categoryId": "%s"
            }
            """.formatted(UUID.randomUUID());

        // When/Then
        mockMvc.perform(patch("/api/v1/products/{id}", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").exists());

        verify(updateProductUseCase).execute(any());
    }

    // ── v1s-12-10: real actor role reaches the Story 2.4 draft guard ─────────

    private MockMvc mockMvcAs(String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
        return MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new com.keevo.shared.infrastructure.web.GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("v1s-12-10: PATCH by EMPLOYEE on a DRAFT product returns 403 (role passed to use case)")
    void update_draft_by_employee_returns_403() throws Exception {
        MockMvc mvc = mockMvcAs("EMPLOYEE");
        when(updateProductUseCase.execute(any())).thenThrow(new com.keevo.shared.domain.exception.DomainException(
                com.keevo.shared.domain.exception.ErrorCode.FORBIDDEN,
                "Seul le propriétaire peut valider un produit en brouillon"));

        mvc.perform(patch("/api/v1/products/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Draft\"}"))
                .andExpect(status().isForbidden());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(updateProductUseCase).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().actorRole()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("v1s-12-10: PATCH by EMPLOYEE on an ACTIVE product returns 200")
    void update_active_by_employee_returns_200() throws Exception {
        MockMvc mvc = mockMvcAs("EMPLOYEE");
        when(updateProductUseCase.execute(any())).thenReturn(sampleProduct);

        mvc.perform(patch("/api/v1/products/{id}", sampleProduct.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("v1s-12-10: PATCH by OWNER passes OWNER role")
    void update_by_owner_passes_owner_role() throws Exception {
        MockMvc mvc = mockMvcAs("OWNER");
        when(updateProductUseCase.execute(any())).thenReturn(sampleProduct);

        mvc.perform(patch("/api/v1/products/{id}", sampleProduct.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Updated\"}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(updateProductUseCase).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().actorRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("PATCH /api/v1/products/{id}/archive should archive product")
    void should_archive_product() throws Exception {
        // Given
        UUID productId = UUID.randomUUID();
        Product archivedProduct = new Product(
            productId, "Test Product", "Test Description", "KEV-TST123",
            UUID.randomUUID(), null, null, null, null, true, ProductStatus.ACTIVE, Instant.now(), Instant.now()
        );
        when(productRepository.findById(productId)).thenReturn(Optional.of(archivedProduct));

        // When/Then
        mockMvc.perform(patch("/api/v1/products/{id}/archive", productId))
                .andExpect(status().isOk());

        verify(archiveProductUseCase).execute(any(ArchiveProductUseCase.ArchiveProductDto.class));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}/pricing should return margin calculation")
    @WithMockUser
    void should_return_pricing_calculation() throws Exception {
        // Given
        UUID productId = UUID.randomUUID();
        var pricingDto = new GetProductPricingUseCase.ProductPricingDto(
                productId, 5000, 3000, 500, 3500, 1500, 30.0, false, MarginThreshold.PROFITABLE
        );
        when(getProductPricingUseCase.execute(productId)).thenReturn(pricingDto);

        // When/Then
        mockMvc.perform(get("/api/v1/products/{id}/pricing", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellingPrice").value(5000))
                .andExpect(jsonPath("$.data.marginPercentage").value(30.0))
                .andExpect(jsonPath("$.data.marginThreshold").value("PROFITABLE"))
                .andExpect(jsonPath("$.data.isLoss").value(false));

        verify(getProductPricingUseCase).execute(productId);
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}/pricing should return 404 when product not found")
    @WithMockUser
    void should_return_404_for_pricing_when_product_not_found() throws Exception {
        // Given
        UUID productId = UUID.randomUUID();
        when(getProductPricingUseCase.execute(productId))
                .thenThrow(new IllegalArgumentException("Product not found: " + productId));

        // When/Then
        mockMvc.perform(get("/api/v1/products/{id}/pricing", productId))
                .andExpect(status().isNotFound());
    }

    // Commented out - security disabled for unit test
    /*
    @Test
    @DisplayName("Should require authentication for all endpoints")
    void should_require_authentication() throws Exception {
        // When/Then - test without @WithMockUser
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized());
    }
    */

    // ── v1s-15-9: actor label never falls back to the raw UUID ────────────

    @Test
    @DisplayName("v1s-15-9: POST by EMPLOYEE without firstName claim passes 'Un employé', not the UUID")
    void create_by_employee_without_firstName_uses_fallback_label() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                actorId, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(com.keevo.shared.infrastructure.security.AuthDetails.employee(null, storeId));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        when(createProductUseCase.execute(any())).thenReturn(sampleProduct);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Test Product\"}"))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateProductUseCase.CreateProductDto.class);
        verify(createProductUseCase).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().actorName())
                .isEqualTo("Un employé")
                .doesNotContain(actorId.toString());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().actorRole()).isEqualTo("EMPLOYEE");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    // ── 12.2a: supplier lookup through the crm public API, same JSON ──────

    @Test
    @DisplayName("GET /api/v1/products/{id}/supplier returns the linked supplier with its productIds")
    void get_product_supplier_returns_supplier_payload() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        when(supplierQueryPort.findByProductId(TenantContext.getCurrentTenant(), productId)).thenReturn(Optional.of(
                new com.keevo.crm.api.SupplierView(supplierId, "Distributeur SA", "+237690000099",
                        "contact@distrib.cm", false, List.of(productId, otherProductId),
                        Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-02T10:00:00Z"))));

        mockMvc.perform(get("/api/v1/products/{id}/supplier", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(supplierId.toString()))
                .andExpect(jsonPath("$.data.name").value("Distributeur SA"))
                .andExpect(jsonPath("$.data.phone").value("+237690000099"))
                .andExpect(jsonPath("$.data.email").value("contact@distrib.cm"))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.productIds.length()").value(2))
                .andExpect(jsonPath("$.data.productIds[1]").value(otherProductId.toString()))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}/supplier returns 404 SUPPLIER_NOT_FOUND when none is linked")
    void get_product_supplier_returns_404_when_none_linked() throws Exception {
        UUID productId = UUID.randomUUID();
        when(supplierQueryPort.findByProductId(TenantContext.getCurrentTenant(), productId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/products/{id}/supplier", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("SUPPLIER_NOT_FOUND"));
    }
}
