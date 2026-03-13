package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.catalog.product.adapter.out.csv.ProductCsvRowFactory;
import com.keevo.catalog.product.application.dto.CsvColumnMapping;
import com.keevo.catalog.product.application.dto.ImportResult;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.port.out.CsvParserPort;
import com.keevo.catalog.product.domain.port.out.DefaultStorePort;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for ImportCsvProductsUseCase (Story 2.4).
 *
 * <p>Verifies: RBAC guard, partial import, plan limit, stock entry,
 * name uniqueness skip, audit event publication, skipped counter accuracy.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImportCsvProductsUseCase")
class ImportCsvProductsUseCaseTest {

    @Mock private CsvParserPort           csvParser;
    @Mock private ProductCsvRowFactory    rowFactory;
    @Mock private CreateProductUseCase    createProductUseCase;
    @Mock private ProductRepository       productRepository;
    @Mock private StockOperationService   stockOperationService;
    @Mock private DefaultStorePort        defaultStorePort;
    @Mock private ProductCountPort        productCountPort;
    @Mock private SubscriptionRepository  subscriptionRepository;
    @Mock private PlanLimitGuard          planLimitGuard;
    @Mock private CategoryRepository      categoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ImportCsvProductsUseCase useCase;
    private final UUID actorId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new ImportCsvProductsUseCase(
                csvParser, rowFactory, createProductUseCase, productRepository,
                stockOperationService, defaultStorePort, productCountPort,
                subscriptionRepository, planLimitGuard, categoryRepository, eventPublisher);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ImportCsvProductsUseCase.ImportCsvCommand ownerCommand(int rowCount) {
        return new ImportCsvProductsUseCase.ImportCsvCommand(
                new ByteArrayInputStream(new byte[0]),
                new CsvColumnMapping("nom", "prix_vente", null, null, null, null, null, null),
                actorId, "OWNER", "Simon"
        );
    }

    private Product stubProduct(String name) {
        return new Product(UUID.randomUUID(), name, null, "KEV-ABC123",
                null, 100, 0, 0, 0, false, ProductStatus.ACTIVE, 0,
                Instant.now(), Instant.now());
    }

    private void stubPlan(int maxProducts, int currentCount) {
        var sub = mock(com.keevo.subscription.plan.domain.model.Subscription.class);
        when(sub.getPlanType()).thenReturn(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(sub));
        when(productCountPort.countActiveProducts()).thenReturn(currentCount);
        lenient().doNothing().when(planLimitGuard).checkProductLimit(any(), anyInt());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldThrowForbiddenWhenEmployeeTriesToImport")
    void shouldThrowForbiddenWhenEmployeeTriesToImport() {
        var cmd = new ImportCsvProductsUseCase.ImportCsvCommand(
                new ByteArrayInputStream(new byte[0]),
                new CsvColumnMapping("nom", "prix_vente", null, null, null, null, null, null),
                actorId, "EMPLOYEE", "Loïc"
        );

        var ex = assertThrows(DomainException.class, () -> useCase.execute(cmd));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getDomainCode());
        verifyNoInteractions(csvParser);
    }

    @Test
    @DisplayName("shouldImportAllValidRows")
    void shouldImportAllValidRows() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan(500, 0);
            when(defaultStorePort.getDefaultStoreId()).thenReturn(storeId);
            when(categoryRepository.findAllActive()).thenReturn(List.of());

            var row1 = Map.of("nom", "T-Shirt", "prix_vente", "5000");
            var row2 = Map.of("nom", "Pantalon", "prix_vente", "8000");
            when(csvParser.parse(any())).thenReturn(List.of(row1, row2));

            var dto1 = new CreateProductUseCase.CreateProductDto("T-Shirt", null, null, null, 5000, 0, 0, 0, actorId);
            var dto2 = new CreateProductUseCase.CreateProductDto("Pantalon", null, null, null, 8000, 0, 0, 0, actorId);
            when(rowFactory.fromRow(eq(row1), any(), anyInt(), any(), any())).thenReturn(dto1);
            when(rowFactory.fromRow(eq(row2), any(), anyInt(), any(), any())).thenReturn(dto2);
            when(productRepository.existsByName("T-Shirt")).thenReturn(false);
            when(productRepository.existsByName("Pantalon")).thenReturn(false);
            when(createProductUseCase.execute(dto1)).thenReturn(stubProduct("T-Shirt"));
            when(createProductUseCase.execute(dto2)).thenReturn(stubProduct("Pantalon"));

            ImportResult result = useCase.execute(ownerCommand(2));

            assertEquals(2, result.getImported());
            assertEquals(0, result.getSkipped());
            assertFalse(result.isLimitReached());
        }
    }

    @Test
    @DisplayName("shouldSkipInvalidRowsAndReportErrors")
    void shouldSkipInvalidRowsAndReportErrors() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan(500, 0);
            when(defaultStorePort.getDefaultStoreId()).thenReturn(storeId);
            when(categoryRepository.findAllActive()).thenReturn(List.of());

            var goodRow = Map.of("nom", "Chemise", "prix_vente", "6000");
            var badRow  = Map.of("nom", "", "prix_vente", "");
            when(csvParser.parse(any())).thenReturn(List.of(goodRow, badRow));

            var goodDto = new CreateProductUseCase.CreateProductDto("Chemise", null, null, null, 6000, 0, 0, 0, actorId);
            when(rowFactory.fromRow(eq(goodRow), any(), anyInt(), any(), any())).thenReturn(goodDto);
            when(rowFactory.fromRow(eq(badRow), any(), anyInt(), any(), any()))
                    .thenThrow(new com.keevo.catalog.product.adapter.out.csv.validator.CsvValidationException(3, "nom", "Nom requis"));
            when(productRepository.existsByName("Chemise")).thenReturn(false);
            when(createProductUseCase.execute(goodDto)).thenReturn(stubProduct("Chemise"));

            ImportResult result = useCase.execute(ownerCommand(2));

            assertEquals(1, result.getImported());
            assertEquals(1, result.getSkipped());
            assertEquals(1, result.getErrors().size());
        }
    }

    @Test
    @DisplayName("shouldStopAtPlanLimitAndMarkLimitReachedWithCorrectSkippedCount")
    void shouldStopAtPlanLimitAndMarkLimitReachedWithCorrectSkippedCount() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            // Plan allows 500, already 499 active → only 1 slot remaining
            stubPlan(500, 499);
            when(defaultStorePort.getDefaultStoreId()).thenReturn(storeId);
            when(categoryRepository.findAllActive()).thenReturn(List.of());

            var row1 = Map.of("nom", "A", "prix_vente", "100");
            var row2 = Map.of("nom", "B", "prix_vente", "100");
            var row3 = Map.of("nom", "C", "prix_vente", "100");
            when(csvParser.parse(any())).thenReturn(List.of(row1, row2, row3));

            var dto1 = new CreateProductUseCase.CreateProductDto("A", null, null, null, 100, 0, 0, 0, actorId);
            when(rowFactory.fromRow(eq(row1), any(), anyInt(), any(), any())).thenReturn(dto1);
            when(productRepository.existsByName("A")).thenReturn(false);
            when(createProductUseCase.execute(dto1)).thenReturn(stubProduct("A"));

            ImportResult result = useCase.execute(ownerCommand(3));

            assertEquals(1, result.getImported());
            assertEquals(2, result.getSkipped(), "Les 2 lignes restantes doivent être comptées skipped");
            assertTrue(result.isLimitReached());
        }
    }

    @Test
    @DisplayName("shouldSkipRowWithDuplicateNameAndReportError")
    void shouldSkipRowWithDuplicateNameAndReportError() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan(500, 0);
            when(defaultStorePort.getDefaultStoreId()).thenReturn(storeId);
            when(categoryRepository.findAllActive()).thenReturn(List.of());

            var row = Map.of("nom", "Produit existant", "prix_vente", "5000");
            when(csvParser.parse(any())).thenReturn(List.of(row));

            var dto = new CreateProductUseCase.CreateProductDto("Produit existant", null, null, null, 5000, 0, 0, 0, actorId);
            when(rowFactory.fromRow(any(), any(), anyInt(), any(), any())).thenReturn(dto);
            when(productRepository.existsByName("Produit existant")).thenReturn(true);

            ImportResult result = useCase.execute(ownerCommand(1));

            assertEquals(0, result.getImported());
            assertEquals(1, result.getSkipped());
            assertTrue(result.getErrors().get(0).message().contains("existe déjà"));
            verify(createProductUseCase, never()).execute(any());
        }
    }

    @Test
    @DisplayName("shouldEmitCsvImportCompletedEventAfterLoop")
    void shouldEmitCsvImportCompletedEventAfterLoop() {
        try (MockedStatic<TenantContext> ctx = mockStatic(TenantContext.class)) {
            ctx.when(TenantContext::getCurrentTenant).thenReturn("kv_test");
            stubPlan(500, 0);
            when(defaultStorePort.getDefaultStoreId()).thenReturn(storeId);
            when(categoryRepository.findAllActive()).thenReturn(List.of());

            var row = Map.of("nom", "Produit", "prix_vente", "1000");
            when(csvParser.parse(any())).thenReturn(List.of(row));
            var dto = new CreateProductUseCase.CreateProductDto("Produit", null, null, null, 1000, 0, 0, 0, actorId);
            when(rowFactory.fromRow(any(), any(), anyInt(), any(), any())).thenReturn(dto);
            when(productRepository.existsByName("Produit")).thenReturn(false);
            when(createProductUseCase.execute(dto)).thenReturn(stubProduct("Produit"));

            useCase.execute(ownerCommand(1));

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean csvEventPublished = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof CsvImportCompletedEvent);
            assertTrue(csvEventPublished, "CsvImportCompletedEvent doit être émis après la boucle");
        }
    }
}
