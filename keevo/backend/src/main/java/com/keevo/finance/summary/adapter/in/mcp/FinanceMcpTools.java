package com.keevo.finance.summary.adapter.in.mcp;

import com.keevo.finance.module.FinanceModule;
import com.keevo.finance.summary.application.FinanceAccess;
import com.keevo.finance.summary.application.FinanceSummaryService;
import com.keevo.finance.summary.domain.model.FinancePeriods;
import com.keevo.finance.summary.domain.model.FinanceSummary;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code finance} module, mirroring {@code FinanceController#summary} (permission
 * {@code FINANCE_VIEW}, module {@code finance} READ, same period forms and the same {@link FinanceAccess}). The
 * result is the REST summary without store ids; Story 21.4.2: the revenue block is renamed for a model — the REST
 * {@code invoiceCount} counts sales <em>and</em> invoices, {@code paid} / {@code unpaid} are totals of fully settled /
 * not fully settled documents (Bref's « payé »), not client debts — which a real model read as « factures » and
 * « reste dû ».
 */
@Component
public class FinanceMcpTools {

    private final FinanceSummaryService summaries;
    private final Clock clock;

    public FinanceMcpTools(FinanceSummaryService summaries, Clock clock) {
        this.summaries = summaries;
        this.clock = clock;
    }

    public record StoreResult(String store, BigDecimal revenue, BigDecimal expenses, BigDecimal result) {
    }

    public record CategoryShare(String category, BigDecimal amount, BigDecimal sharePct) {
    }

    /**
     * The revenue of the period, named for a model: {@code fullyPaidDocumentsTotal} / {@code
     * notFullyPaidDocumentsTotal} are whole documents (a partly paid invoice counts in full in the second), never what
     * clients still owe ({@code unpaid_call_list}).
     */
    public record RevenueFigures(BigDecimal revenueInclVat, BigDecimal vat, BigDecimal revenueExclVat,
                                 BigDecimal fullyPaidDocumentsTotal, BigDecimal notFullyPaidDocumentsTotal,
                                 long salesAndInvoicesCount) {

        static RevenueFigures of(FinanceSummary.Revenue r) {
            return r == null ? null : new RevenueFigures(r.ttc(), r.tax(), r.excludingTax(), r.paid(), r.unpaid(),
                    r.invoiceCount());
        }
    }

    /** The period, its granularity in French ({@code jour} / {@code mois}: no English key reaches a model). */
    public record PeriodView(LocalDate startDate, LocalDate endDate, long days, String granularity) {

        static PeriodView of(FinanceSummary.PeriodInfo p) {
            return p == null ? null : new PeriodView(p.startDate(), p.endDate(), p.days(),
                    "month".equals(p.granularity()) ? "mois" : "jour");
        }
    }

    public record FinancialResult(PeriodView period, RevenueFigures revenue,
                                  FinanceSummary.Expenses expenses, FinanceSummary.Result result,
                                  FinanceSummary.Comparison comparisonWithPreviousPeriod,
                                  List<CategoryShare> expensesByCategory, List<FinanceSummary.SeriesPoint> series,
                                  List<StoreResult> byStore) {
    }

    @Tool(name = "financial_result", description = "Résultat financier (bénéfice ou perte) d'une période : chiffre "
            + "d'affaires TTC, TVA et HT ; total des documents entièrement réglés et des documents pas encore "
            + "entièrement réglés (montants complets des documents, pas ce que doivent les clients : voir "
            + "unpaid_call_list) ; nombre de ventes en caisse et de factures confondues ; dépenses (total, nombre, "
            + "moyenne par jour) ; résultat net (CA HT − dépenses), marge nette et part des dépenses ; comparaison "
            + "avec la période précédente de même durée ; dépenses par catégorie ; évolution par jour ou par mois ; "
            + "résultat par boutique. Pour le détail des dépenses, utiliser list_expenses ; pour la marge brute, "
            + "revenue_summary. Période : period = AAAA-MM ou AAAA, ou startDate et endDate (AAAA-MM-JJ) ; par défaut "
            + "le mois en cours. Réservé au propriétaire. Montants en FCFA.")
    @RequiresPermission(FinanceModule.FINANCE_VIEW)
    @RequiresEntitlement(value = FinanceModule.ID, access = RequiresEntitlement.Access.READ)
    public FinancialResult financialResult(
            @ToolParam(required = false, description = "Mois AAAA-MM ou année AAAA") String period,
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ)") String startDate,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ)") String endDate,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores) ; absent = "
                    + "toutes les boutiques") UUID storeId) {
        FinancePeriods.Resolved resolved;
        try {
            resolved = FinancePeriods.resolve(period, startDate, endDate, LocalDate.now(clock.withZone(KeevoZones.WAT)));
        } catch (FinancePeriods.InvalidPeriodException e) {
            throw new InvalidRequestParameterException(e.parameter(), "invalid finance period", e.getMessage());
        }
        // same access as FinanceController
        FinanceAccess access = CurrentActor.isOwner() ? FinanceAccess.OWNER
                : FinanceAccess.member(CurrentActor.assignedStoreIdOrNull());
        FinanceSummary s = summaries.summary(resolved, storeId, access, TenantContext.getCurrentTenant());
        return new FinancialResult(PeriodView.of(s.period()), RevenueFigures.of(s.revenue()), s.expenses(), s.result(), s.comparison(),
                s.byCategory() == null ? List.of() : s.byCategory().stream()
                        .map(c -> new CategoryShare(c.categoryLabel(), c.amount(), c.sharePct())).toList(),
                s.series() == null ? List.of() : s.series(),
                s.byStore() == null ? List.of() : s.byStore().stream()
                        .map(r -> new StoreResult(r.storeName(), r.revenue(), r.expenses(), r.result())).toList());
    }
}
