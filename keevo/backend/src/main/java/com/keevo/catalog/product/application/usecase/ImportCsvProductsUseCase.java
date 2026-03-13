package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.catalog.product.adapter.out.csv.ProductCsvRowFactory;
import com.keevo.catalog.product.adapter.out.csv.validator.CsvValidationException;
import com.keevo.catalog.product.application.dto.CsvColumnMapping;
import com.keevo.catalog.product.application.dto.CsvRowError;
import com.keevo.catalog.product.application.dto.ImportResult;
import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.event.ProductImportedFromCsvEvent;
import com.keevo.catalog.product.domain.port.out.CsvParserPort;
import com.keevo.catalog.product.domain.port.out.DefaultStorePort;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ImportCsvProductsUseCase — batch import products from a CSV file.
 *
 * <p><b>GoF patterns:</b>
 * <ul>
 *   <li><b>Builder</b>  — {@link ImportResult.Builder} accumulates import state progressively.</li>
 *   <li><b>Strategy</b> — column validation delegated to {@code CsvColumnValidator<T>} implementors
 *       inside {@link ProductCsvRowFactory}.</li>
 *   <li><b>Factory Method</b> — row → DTO transformation in {@link ProductCsvRowFactory#fromRow}.</li>
 * </ul>
 *
 * <p>RBAC: OWNER only (AC3). Employees receive a 403 FORBIDDEN.
 *
 * <p>Plan limit (AC4): import stops when the tenant's active-product ceiling is reached.
 * Already-processed rows are committed; the response marks {@code limitReached = true}.
 *
 * <p>Name uniqueness (AC8): rows with a duplicate name are individually skipped (not aborted).
 *
 * <p>Story 2.4.
 */
@Service
public class ImportCsvProductsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImportCsvProductsUseCase.class);

    private final CsvParserPort           csvParser;
    private final ProductCsvRowFactory    rowFactory;
    private final CreateProductUseCase    createProductUseCase;
    private final ProductRepository       productRepository;
    private final StockOperationService   stockOperationService;
    private final DefaultStorePort        defaultStorePort;
    private final ProductCountPort        productCountPort;
    private final SubscriptionRepository  subscriptionRepository;
    private final PlanLimitGuard          planLimitGuard;
    private final CategoryRepository      categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ImportCsvProductsUseCase(
            CsvParserPort csvParser,
            ProductCsvRowFactory rowFactory,
            CreateProductUseCase createProductUseCase,
            ProductRepository productRepository,
            StockOperationService stockOperationService,
            DefaultStorePort defaultStorePort,
            ProductCountPort productCountPort,
            SubscriptionRepository subscriptionRepository,
            PlanLimitGuard planLimitGuard,
            CategoryRepository categoryRepository,
            ApplicationEventPublisher eventPublisher) {
        this.csvParser             = csvParser;
        this.rowFactory            = rowFactory;
        this.createProductUseCase  = createProductUseCase;
        this.productRepository     = productRepository;
        this.stockOperationService = stockOperationService;
        this.defaultStorePort      = defaultStorePort;
        this.productCountPort      = productCountPort;
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard        = planLimitGuard;
        this.categoryRepository    = categoryRepository;
        this.eventPublisher        = eventPublisher;
    }

    /**
     * Command object for the CSV import.
     *
     * @param csvStream   the raw multipart input stream
     * @param mapping     column-name bindings resolved from the request DTO
     * @param actorId     authenticated user UUID
     * @param actorRole   "OWNER" or "EMPLOYEE" — must be "OWNER"
     * @param actorName   display name for notifications
     */
    public record ImportCsvCommand(
            InputStream    csvStream,
            CsvColumnMapping mapping,
            UUID           actorId,
            String         actorRole,
            String         actorName
    ) {}

    /**
     * Execute the CSV import with full plan-limit, RBAC and name-uniqueness guards.
     *
     * <p>NOT wrapped in a single {@code @Transactional}: each row is its own DB operation
     * so that partial imports survive (already-committed rows are preserved if the loop
     * stops at the plan limit or on a network error).
     *
     * @param command import command
     * @return {@link ImportResult} — imported count + skipped rows + optional limit message
     */
    public ImportResult execute(ImportCsvCommand command) {

        // ── 1. RBAC ──────────────────────────────────────────────────────────
        if (!"OWNER".equals(command.actorRole())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut importer des produits via CSV");
        }

        // ── 2. Parse CSV ─────────────────────────────────────────────────────
        List<Map<String, String>> rows = csvParser.parse(command.csvStream());

        // ── 3. Determine remaining product capacity ──────────────────────────
        int currentCount = productCountPort.countActiveProducts();
        var planType = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif trouvé"))
                .getPlanType();

        int maxProducts = planType.getMaxProducts();
        int remaining   = maxProducts - currentCount;

        // ── 4. Build category name→UUID map (case-insensitive) ────────────────
        Map<String, UUID> categoryNameToId = categoryRepository.findAllActive().stream()
                .collect(Collectors.toMap(
                        c -> c.name().toLowerCase(),
                        c -> c.id(),
                        (a, b) -> a   // keep first on duplicate names
                ));

        // ── 5. Resolve default store (for stock movement) ─────────────────────
        UUID storeId = defaultStorePort.getDefaultStoreId();

        // ── 6. Import loop ────────────────────────────────────────────────────
        var builder  = new ImportResult.Builder();
        String tenantId = TenantContext.getCurrentTenant();
        int lineNumber  = 1; // 1-based data line (header not counted)
        boolean limitHit = false;

        for (Map<String, String> row : rows) {
            lineNumber++;

            // Once the plan limit is hit, count all remaining rows as skipped
            // (no break — we iterate to the end so the skipped counter is accurate)
            if (remaining <= 0) {
                if (!limitHit) {
                    builder.markLimitReached(
                            "Limite du plan atteinte (" + maxProducts + " produits maximum)");
                    limitHit = true;
                }
                builder.incrementSkipped();
                continue;
            }

            try {
                // Factory Method: CSV row → CreateProductDto
                var dto = rowFactory.fromRow(row, command.mapping(), lineNumber,
                        command.actorId(), categoryNameToId);

                // Name uniqueness check (AC8) — skip row gracefully
                if (productRepository.existsByName(dto.name())) {
                    builder.addError(new CsvRowError(lineNumber, "nom",
                            "Un produit avec ce nom existe déjà: " + dto.name()));
                    continue;
                }

                // Create product via use case (saves + publishes ProductCreatedEvent)
                var saved = createProductUseCase.execute(dto);

                // Stock movement if initial quantity > 0
                if (dto.stockQuantity() != null && dto.stockQuantity() > 0) {
                    stockOperationService.recordOperation(
                            saved.getId(),
                            null,                   // no variant
                            storeId,
                            MovementType.STOCK_ENTRY,  // initial stock entry
                            dto.stockQuantity(),
                            command.actorId(),
                            "Import CSV ligne " + lineNumber
                    );
                }

                // Per-product domain event
                eventPublisher.publishEvent(new ProductImportedFromCsvEvent(
                        saved.getId(),
                        saved.getName(),
                        command.actorId(),
                        tenantId,
                        lineNumber,
                        Instant.now()
                ));

                builder.incrementImported();
                remaining--;

            } catch (CsvValidationException e) {
                builder.addError(new CsvRowError(e.getLineNumber(), e.getColumnName(), e.getMessage()));
                log.debug("CSV validation error at line {}: {}", lineNumber, e.getMessage());
            } catch (DomainException e) {
                // Catch any domain errors (e.g. SKU collision after retry) — skip row
                builder.addError(new CsvRowError(lineNumber, "-", e.getDomainCode() + ": " + e.getMessage()));
                log.debug("Domain error at CSV line {}: {}", lineNumber, e.getMessage());
            }
        }

        // ── 7. Batch notification event ────────────────────────────────────────
        ImportResult result = builder.build();
        if (result.getImported() > 0) {
            eventPublisher.publishEvent(new CsvImportCompletedEvent(
                    result.getImported(),
                    tenantId,
                    command.actorId(),
                    command.actorName(),
                    command.actorRole(),
                    Instant.now()
            ));
        }

        log.info("CSV import completed: {} imported, {} skipped, limitReached={}",
                result.getImported(), result.getSkipped(), result.isLimitReached());

        return result;
    }
}
