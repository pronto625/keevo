package com.keevo.commerce.sale.adapter.in.mcp;

import com.keevo.commerce.sale.adapter.in.SalesReadScope;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase.SalesHistoryQuery;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.ai.AiToolArguments;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code commerce} module, mirroring {@code SaleHistoryController} (every member role,
 * module {@code pos} READ) through the same policy ({@link SalesReadScope}, Story 19.5b): a member holding
 * {@code STORE_SALES_VIEW} (MANAGER, VIEWER) sees every sale of his store, another store member (EMPLOYEE) his own
 * sales, the owner every seller's; a member asking another store is refused. Same defaults (last 30 days, WAT days). No sale, product, seller or client id is returned.
 */
@Component
public class SalesMcpTools {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int DEFAULT_PERIOD_DAYS = 30;

    static final Map<String, String> STATUS = Map.of(
            "COMPLETED", "Validée", "CANCELLED", "Annulée", "PENDING_VALIDATION", "En attente de validation");
    static final Map<String, String> PAYMENT = Map.of("CASH", "Espèces", "MOBILE_MONEY", "Mobile Money");

    private final GetSalesHistoryUseCase history;
    private final Clock clock;
    private final SalesReadScope scope; // Story 19.5b

    public SalesMcpTools(GetSalesHistoryUseCase history, Clock clock, SalesReadScope scope) {
        this.history = history;
        this.clock = clock;
        this.scope = scope;
    }

    public record SaleLine(String product, int quantity, int unitPrice, int subtotal) {
    }

    public record SaleRow(LocalDateTime at, String status, String payment, int total, int discount,
                          List<SaleLine> lines) {
    }

    public record SalesPage(LocalDate from, LocalDate to, boolean ownSalesOnly, int page, int size,
                            long totalSales, int totalPages, List<SaleRow> sales) {
    }

    @Tool(name = "sales_history", description = "Historique des ventes en caisse d'une boutique sur une période "
            + "(date et heure, statut, paiement, total, remise, articles), les plus récentes d'abord, par pages. "
            + "Un employé ne voit que ses propres ventes ; un gérant ou un lecteur voit celles de sa boutique. Par défaut les 30 derniers jours et "
            + "la boutique de l'employé ; le propriétaire indique storeId (outil list_stores). Montants en FCFA.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "pos", access = RequiresEntitlement.Access.READ)
    public SalesPage salesHistory(
            @ToolParam(required = false, description = "Identifiant de la boutique (outil list_stores) ; par défaut "
                    + "la boutique de l'employé") UUID storeId,
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ), par défaut il y a 30 jours")
            LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ), par défaut aujourd'hui")
            LocalDate to,
            @ToolParam(required = false, description = "Numéro de page, à partir de 0") Integer page,
            @ToolParam(required = false, description = "Ventes par page, 1 à 50 (défaut 20)") Integer size) {
        SalesReadScope.Resolved resolved = scope.resolve(storeId, null); // Story 19.5b
        UUID store = resolved.store();
        if (store == null) {
            throw new InvalidRequestParameterException("storeId", "store required",
                    "Précisez la boutique (outil list_stores).");
        }
        LocalDate today = LocalDate.now(clock.withZone(KeevoZones.WAT));
        LocalDate dateTo = to != null ? to : today;
        LocalDate dateFrom = from != null ? from : today.minusDays(DEFAULT_PERIOD_DAYS - 1); // 30 days, today included
        AiToolArguments.checkPeriod(dateFrom, dateTo);
        int pageNumber = AiToolArguments.page(page);
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        boolean own = resolved.ownSalesOnly();
        Page<Sale> sales = history.getSalesHistory(new SalesHistoryQuery(store, resolved.employeeFilter(),
                dateFrom.atStartOfDay(KeevoZones.WAT).toInstant(),
                dateTo.plusDays(1).atStartOfDay(KeevoZones.WAT).toInstant(), pageNumber, pageSize));
        List<SaleRow> rows = sales.getContent().stream().map(SalesMcpTools::row).toList();
        return new SalesPage(dateFrom, dateTo, own, sales.getNumber(), sales.getSize(), sales.getTotalElements(),
                sales.getTotalPages(), rows);
    }

    private static SaleRow row(Sale sale) {
        List<SaleLine> lines = sale.getItems() == null ? List.of() : sale.getItems().stream()
                .map(SalesMcpTools::line).toList();
        return new SaleRow(sale.getOccurredAt() == null ? null
                : LocalDateTime.ofInstant(sale.getOccurredAt(), KeevoZones.WAT),
                STATUS.getOrDefault(sale.getStatus().name(), "Autre statut"),
                sale.getPaymentMode() == null ? null
                        : PAYMENT.getOrDefault(sale.getPaymentMode().name(), "Autre moyen"),
                sale.getTotalAmount(), sale.getDiscountAmount(), lines);
    }

    private static SaleLine line(SaleItem item) {
        return new SaleLine(item.getProductName(), item.getQuantity(), item.getAppliedUnitPrice(), item.getSubtotal());
    }
}
