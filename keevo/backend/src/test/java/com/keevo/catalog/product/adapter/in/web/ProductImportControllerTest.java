package com.keevo.catalog.product.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.dto.ImportResult;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.GenerateCsvTemplateUseCase;
import com.keevo.catalog.product.application.usecase.ImportCsvProductsUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.messaging.notification.application.usecase.GetPendingDraftsCountUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ProductImportController — Story 2.4.
 *
 * <p>Uses standalone MockMvc to keep tests fast and focused on the controller layer.
 * The {@link GlobalExceptionHandler} is included so DomainException → HTTP status mapping
 * is exercised.
 */
@ExtendWith(MockitoExtension.class)
class ProductImportControllerTest {

    @Mock private ImportCsvProductsUseCase     importUseCase;
    @Mock private CreateDraftProductUseCase    draftUseCase;
    @Mock private GenerateCsvTemplateUseCase   templateUseCase;
    @Mock private GetPendingDraftsCountUseCase pendingCountUseCase;

    @InjectMocks
    private ProductImportController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID actorId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        controller = new ProductImportController(
                importUseCase, draftUseCase, templateUseCase, pendingCountUseCase, objectMapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        actorId = UUID.randomUUID();
    }

    // ── Authentication helpers ────────────────────────────────────────────────

    private void authenticateAs(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── AC1: Template download ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /import/template → 200 with CSV bytes")
    void shouldReturn200WithCsvTemplateBytes() throws Exception {
        byte[] csv = "nom,prix_vente\nProduit A,1000\n".getBytes();
        when(templateUseCase.execute()).thenReturn(csv);
        authenticateAs("OWNER");

        mockMvc.perform(get("/api/v1/products/import/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("keevo-produits-template.csv")));
    }

    // ── AC2–AC4: CSV import ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /import → 200 with ImportResult when CSV is valid")
    void shouldReturn200WithImportResultOnValidCsvUpload() throws Exception {
        authenticateAs("OWNER");

        ImportResult result = buildResult(3, 0, false, null);
        when(importUseCase.execute(any())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv",
                "nom,prix_vente\nProduit A,1000\nProduit B,2000\nProduit C,3000\n".getBytes());
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"nameColumn\":\"nom\",\"priceColumn\":\"prix_vente\"}".getBytes());

        mockMvc.perform(multipart("/api/v1/products/import")
                        .file(file)
                        .file(mapping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(3))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.limitReached").value(false));
    }

    @Test
    @DisplayName("POST /import → 400 when nameColumn is blank in mapping")
    void shouldReturn400WhenNameColumnIsMissing() throws Exception {
        authenticateAs("OWNER");

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", "prix_vente\n1000\n".getBytes());
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"nameColumn\":\"\",\"priceColumn\":\"prix_vente\"}".getBytes());

        mockMvc.perform(multipart("/api/v1/products/import")
                        .file(file)
                        .file(mapping))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /import → 200 with limitReached=true when plan limit is hit")
    void shouldReturn200WithLimitReachedWhenPlanExceeded() throws Exception {
        authenticateAs("OWNER");

        ImportResult result = buildResult(1, 4, true, "Limite du plan atteinte (1 produit maximum)");
        when(importUseCase.execute(any())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv",
                "nom,prix_vente\nA,100\nB,200\nC,300\nD,400\nE,500\n".getBytes());
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"nameColumn\":\"nom\",\"priceColumn\":\"prix_vente\"}".getBytes());

        mockMvc.perform(multipart("/api/v1/products/import")
                        .file(file)
                        .file(mapping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.skipped").value(4))
                .andExpect(jsonPath("$.data.limitReached").value(true));
    }

    @Test
    @DisplayName("POST /import → 403 when use case throws FORBIDDEN (non-OWNER role)")
    void shouldReturn403WhenImportUseCaseThrowsForbidden() throws Exception {
        authenticateAs("EMPLOYEE");

        when(importUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.FORBIDDEN,
                        "Seul le propriétaire peut importer des produits via CSV"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", "nom,prix_vente\nA,100\n".getBytes());
        MockMultipartFile mapping = new MockMultipartFile(
                "mapping", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"nameColumn\":\"nom\",\"priceColumn\":\"prix_vente\"}".getBytes());

        mockMvc.perform(multipart("/api/v1/products/import")
                        .file(file)
                        .file(mapping))
                .andExpect(status().isForbidden());
    }

    // ── AC5: Create draft product ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /draft → 201 with created draft product")
    void shouldReturn201WithDraftProductOnDraftEndpoint() throws Exception {
        authenticateAs("EMPLOYEE");

        Product draft = buildDraftProduct("Nouveau Produit");
        when(draftUseCase.execute(any())).thenReturn(draft);

        String requestBody = "{\"name\":\"Nouveau Produit\",\"price\":1500}";

        mockMvc.perform(post("/api/v1/products/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Nouveau Produit"));
    }

    @Test
    @DisplayName("POST /draft → 409 when draft use case throws PRODUCT_NAME_ALREADY_EXISTS")
    void shouldReturn409WhenDraftCreatedWithDuplicateName() throws Exception {
        authenticateAs("EMPLOYEE");

        when(draftUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                        "Un produit avec ce nom existe déjà"));

        String requestBody = "{\"name\":\"Produit Existant\",\"price\":1500}";

        mockMvc.perform(post("/api/v1/products/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());
    }

    // ── AC6: Pending drafts counter ───────────────────────────────────────────

    @Test
    @DisplayName("GET /drafts/count → 200 with count for OWNER")
    void shouldReturn200WithPendingDraftsCountForOwner() throws Exception {
        authenticateAs("OWNER");
        when(pendingCountUseCase.execute("OWNER")).thenReturn(3L);

        mockMvc.perform(get("/api/v1/products/drafts/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    @DisplayName("GET /drafts/count → 403 when use case throws FORBIDDEN (EMPLOYEE role)")
    void shouldReturn403WhenGetPendingCountThrowsForbidden() throws Exception {
        authenticateAs("EMPLOYEE");

        when(pendingCountUseCase.execute("EMPLOYEE"))
                .thenThrow(new DomainException(ErrorCode.FORBIDDEN,
                        "Seul le propriétaire peut consulter les brouillons en attente"));

        mockMvc.perform(get("/api/v1/products/drafts/count"))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ImportResult buildResult(int imported, int skipped, boolean limitReached, String limitMessage) {
        ImportResult.Builder b = new ImportResult.Builder();
        for (int i = 0; i < imported; i++) b.incrementImported();
        for (int i = 0; i < skipped; i++) b.incrementSkipped();
        if (limitReached) b.markLimitReached(limitMessage);
        return b.build();
    }

    private Product buildDraftProduct(String name) {
        return new Product(
                UUID.randomUUID(),
                name,
                null,
                "KEV-TST001",
                null,
                1500,
                null,
                null,
                0,
                false,
                ProductStatus.DRAFT,
                Instant.now(),
                Instant.now()
        );
    }
}
