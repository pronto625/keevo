package com.keevo.catalog.product.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.ProductCountPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Story 20.1 — barcode / product key / manual price on creation and update, and the merged-product guards of the
 * restore paths (create-or-restore answers the target, unarchive refused).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Catalogue étendu — code-barres, clé, prix manuel, produit fusionné (20.1)")
class ProductBrefFieldsUseCasesTest {

    @Mock ProductRepository productRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ProductCountPort productCountPort;
    @Mock TenantPlanQuery tenantPlanQuery;
    @Mock PlanLimitCheck planLimit;
    @Mock TransactionLockPort transactionLock;
    @Mock SetProductStockTrackingUseCase stockTracking;

    private final UUID actor = UUID.randomUUID();
    private final PlanView free = new PlanView(PlanType.FREE, PlanKind.FREE, true, null, 1, 500, 2);

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_bref01");
        when(tenantPlanQuery.findCurrentPlan(any())).thenReturn(Optional.of(free));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Product existing(UUID id, String name, String barcode, boolean priceManual, boolean archived,
                                    UUID mergedInto) {
        return new Product(id, name, null, "KEV-EXIST1", null, 1000, 0, 0, 0, archived, ProductStatus.ACTIVE, 0,
                true, barcode, priceManual, mergedInto, Instant.now(), Instant.now());
    }

    @Nested
    @DisplayName("Création")
    class Creation {

        private CreateProductUseCase useCase() {
            return new CreateProductUseCase(productRepository, eventPublisher, productCountPort, tenantPlanQuery,
                    planLimit, transactionLock);
        }

        private CreateProductUseCase.CreateProductDto dto(String name, Integer price, String barcode) {
            return new CreateProductUseCase.CreateProductDto(name, null, null, null, price, 0, 0, null, actor, 0,
                    null, "OWNER", null, null, null, null, barcode);
        }

        @Test
        @DisplayName("« CRÈME Brûlée » sans code → clé nm:creme brulee, prix manuel")
        void key_from_the_name() {
            Product p = useCase().execute(dto("CRÈME Brûlée", 1500, null));
            assertThat(p.getProductKey()).isEqualTo("nm:creme brulee");
            assertThat(p.isPriceManual()).isTrue();
            assertThat(p.getBarcode()).isNull();
            assertThat(p.getMergedIntoId()).isNull();
        }

        @Test
        @DisplayName("code-barres 6111234567890 → clé bc:, code conservé nettoyé")
        void key_from_the_barcode() {
            Product p = useCase().execute(dto("Savon", 500, " 6111234567890 "));
            assertThat(p.getBarcode()).isEqualTo("6111234567890");
            assertThat(p.getProductKey()).isEqualTo("bc:6111234567890");
        }

        @Test
        @DisplayName("référence libre REF-001 → clé du nom")
        void free_reference_keeps_the_name_key() {
            Product p = useCase().execute(dto("Savon", 500, "REF-001"));
            assertThat(p.getBarcode()).isEqualTo("REF-001");
            assertThat(p.getProductKey()).isEqualTo("nm:savon");
        }

        @Test
        @DisplayName("sans prix → prix non manuel")
        void no_price_is_not_manual() {
            assertThat(useCase().execute(dto("Savon", null, null)).isPriceManual()).isFalse();
        }

        @Test
        @DisplayName("code de plus de 100 caractères → 422, rien n'est enregistré")
        void barcode_too_long_is_422() {
            assertThatThrownBy(() -> useCase().execute(dto("Savon", 500, "1".repeat(101))))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Modification")
    class Update {

        private final UUID id = UUID.randomUUID();

        private UpdateProductUseCase useCase() {
            return new UpdateProductUseCase(productRepository, eventPublisher, new ObjectMapper(), productCountPort,
                    tenantPlanQuery, planLimit, transactionLock, stockTracking);
        }

        private Product run(Product current, String name, Integer price, String barcode) {
            when(productRepository.findById(id)).thenReturn(Optional.of(current));
            useCase().execute(new UpdateProductUseCase.UpdateProductDto(id, name, null, null, null, price, null,
                    null, null, actor, "OWNER", null, barcode));
            var saved = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(saved.capture());
            return saved.getValue();
        }

        @Test
        @DisplayName("corps V1 sans barcode → code inchangé, prix manuel inchangé")
        void v1_body_keeps_the_barcode() {
            Product saved = run(existing(id, "Savon", "6111234567890", false, false, null), "Savon doux", null, null);
            assertThat(saved.getBarcode()).isEqualTo("6111234567890");
            assertThat(saved.getProductKey()).isEqualTo("bc:6111234567890");
            assertThat(saved.isPriceManual()).isFalse();
        }

        @Test
        @DisplayName("barcode vide → effacé, clé recalculée depuis le nom")
        void blank_barcode_clears_it() {
            Product saved = run(existing(id, "Savon", "6111234567890", true, false, null), null, null, "  ");
            assertThat(saved.getBarcode()).isNull();
            assertThat(saved.getProductKey()).isEqualTo("nm:savon");
        }

        @Test
        @DisplayName("renommage → clé recalculée ; prix saisi → prix manuel")
        void rename_and_price() {
            Product saved = run(existing(id, "Savon", null, false, false, null), "Thé VERT", 900, null);
            assertThat(saved.getProductKey()).isEqualTo("nm:the vert");
            assertThat(saved.isPriceManual()).isTrue();
        }

        @Test
        @DisplayName("trackStock + code-barres valide → suivi changé, code conservé et clé bc:")
        void track_stock_and_barcode() {
            Product current = existing(id, "Savon", null, true, false, null);
            Product untracked = new Product(id, "Savon", null, "KEV-EXIST1", null, 1000, 0, 0, 0, false,
                    ProductStatus.ACTIVE, 0, false, null, true, null, Instant.now(), Instant.now());
            when(productRepository.findById(id)).thenReturn(Optional.of(current), Optional.of(untracked));

            useCase().execute(new UpdateProductUseCase.UpdateProductDto(id, null, null, null, null, null, null,
                    null, null, actor, "OWNER", false, " 6111234567890 "));

            verify(stockTracking).execute(id, false, actor, "OWNER");
            var saved = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(saved.capture());
            assertThat(saved.getValue().getBarcode()).isEqualTo("6111234567890");
            assertThat(saved.getValue().getProductKey()).isEqualTo("bc:6111234567890");
            assertThat(saved.getValue().isTrackStock()).isFalse();
        }

        @Test
        @DisplayName("nom et code inchangés → la clé stockée est gardée telle quelle (pas de recalcul)")
        void unchanged_name_and_barcode_keep_the_stored_key() {
            Product stored = new Product(id, "Savon", null, "KEV-EXIST1", null, 1000, 0, 0, 0, false,
                    ProductStatus.ACTIVE, 0, true, null, null, true, null, Instant.now(), Instant.now());
            Product saved = run(stored, "Savon", 1200, null);
            assertThat(saved.getProductKey()).isNull(); // not keyed yet: left to the backfill
        }

        @Test
        @DisplayName("code de plus de 100 caractères → 422 avant toute écriture")
        void too_long_is_422() {
            when(productRepository.findById(id)).thenReturn(Optional.of(existing(id, "Savon", null, true, false, null)));
            assertThatThrownBy(() -> useCase().execute(new UpdateProductUseCase.UpdateProductDto(id, null, null, null,
                    null, null, null, null, null, actor, "OWNER", true, "x".repeat(101))))
                    .isInstanceOf(DomainException.class);
            verify(productRepository, never()).save(any());
            verifyNoInteractions(stockTracking);
        }
    }

    @Nested
    @DisplayName("Produit fusionné")
    class Merged {

        private final UUID a = UUID.randomUUID();
        private final UUID b = UUID.randomUUID();

        @Test
        @DisplayName("désarchivage d'un produit fusionné → 409 PRODUCT_MERGE_INVALID avec le nom de la cible")
        void unarchive_merged_is_refused() {
            when(productRepository.findById(a)).thenReturn(Optional.of(existing(a, "Savon", null, true, true, b)));
            when(productRepository.findById(b)).thenReturn(Optional.of(existing(b, "Savon Lux", null, true, false, null)));
            var useCase = new UnarchiveProductUseCase(productRepository, eventPublisher, productCountPort,
                    tenantPlanQuery, planLimit, transactionLock);

            assertThatThrownBy(() -> useCase.execute(new UnarchiveProductUseCase.UnarchiveProductDto(a, actor)))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_MERGE_INVALID);
                        assertThat(e.getUserMessage()).isEqualTo("Ce produit a été fusionné avec « Savon Lux »");
                    });
            verify(productRepository, never()).unarchive(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("création au nom d'un produit fusionné → la cible (merged), A reste archivé")
        void create_with_a_merged_name_answers_the_target() {
            Product target = existing(b, "Savon Lux", null, true, false, null);
            when(productRepository.findByName("savon")).thenReturn(Optional.of(existing(a, "Savon", null, true, true, b)));
            when(productRepository.findById(b)).thenReturn(Optional.of(target));
            var createProduct = mock(CreateProductUseCase.class);
            var unarchive = mock(UnarchiveProductUseCase.class);
            var useCase = new CreateOrRestoreProductUseCase(createProduct, unarchive, productRepository,
                    transactionLock);

            var outcome = useCase.execute(new CreateProductUseCase.CreateProductDto("savon", null, null, null, 700,
                    0, 0, 0, actor));

            assertThat(outcome.product()).isSameAs(target);
            assertThat(outcome.merged()).isTrue();
            assertThat(outcome.restored()).isFalse();
            verifyNoInteractions(createProduct, unarchive);
        }

        @Test
        @DisplayName("création au nom d'un produit fusionné dont la cible a été archivée → la cible est restaurée, jamais A")
        void create_with_a_merged_name_restores_the_archived_target() {
            Product archivedTarget = existing(b, "Savon Lux", null, true, true, null);
            Product restoredTarget = existing(b, "Savon Lux", null, true, false, null);
            when(productRepository.findByName("savon")).thenReturn(Optional.of(existing(a, "Savon", null, true, true, b)));
            when(productRepository.findById(b)).thenReturn(Optional.of(archivedTarget), Optional.of(restoredTarget));
            var createProduct = mock(CreateProductUseCase.class);
            var unarchive = mock(UnarchiveProductUseCase.class);
            when(unarchive.execute(any())).thenReturn(true);
            var useCase = new CreateOrRestoreProductUseCase(createProduct, unarchive, productRepository,
                    transactionLock);

            var outcome = useCase.execute(new CreateProductUseCase.CreateProductDto("savon", null, null, null, 700,
                    0, 0, 0, actor));

            assertThat(outcome.restored()).isTrue();
            assertThat(outcome.product().getId()).isEqualTo(b);
            var restored = ArgumentCaptor.forClass(UnarchiveProductUseCase.UnarchiveProductDto.class);
            verify(unarchive).execute(restored.capture());
            assertThat(restored.getValue().productId()).isEqualTo(b);
            verifyNoInteractions(createProduct);
        }

        @Test
        @DisplayName("archivage d'un produit fusionné → rien (pas d'écriture, pas d'audit)")
        void archive_merged_is_a_no_op() {
            when(productRepository.findById(a)).thenReturn(Optional.of(existing(a, "Savon", null, true, true, b)));

            new ArchiveProductUseCase(productRepository, eventPublisher)
                    .execute(new ArchiveProductUseCase.ArchiveProductDto(a, actor));

            verify(productRepository, never()).archive(any());
            verifyNoInteractions(eventPublisher);
        }
    }
}
