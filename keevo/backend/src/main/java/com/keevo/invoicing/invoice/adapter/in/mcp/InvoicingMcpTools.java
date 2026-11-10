package com.keevo.invoicing.invoice.adapter.in.mcp;

import com.keevo.invoicing.invoice.application.usecase.DocumentAccess;
import com.keevo.invoicing.invoice.application.usecase.DocumentStoreResolver;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.domain.model.DocumentScope;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.ai.AiToolArguments;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code invoicing} module, mirroring {@code InvoiceController#list} (permission
 * {@code INVOICE_VIEW}, module {@code invoicing} READ) and its document visibility (Story 20.14,
 * {@link DocumentAccess#forList}): the owner sees every document (or one store), a manager or viewer their store, an
 * employee the documents they created; only the owner may filter on a store. Amounts due are computed by the same
 * use case as the REST list (credit notes and received payments included). No document, client or payment id.
 */
@Component
public class InvoicingMcpTools {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int DEFAULT_CLIENTS = 15;
    static final int MAX_CLIENTS = 30;
    static final int DEFAULT_PERIOD_DAYS = 30;
    static final int MAX_SEARCH_LENGTH = 100;
    static final String NO_METHOD = "Non précisé";
    static final String OTHER_METHODS = "Autres moyens";
    static final String NO_CLIENT = "Client sans nom";

    static final Set<PaymentState> OUTSTANDING = Set.of(PaymentState.UNPAID, PaymentState.PARTIAL,
            PaymentState.OVERDUE);

    private final InvoiceQueryUseCase queries;
    private final DocumentAccess access;
    private final DocumentStoreResolver stores;
    private final Clock clock;

    public InvoicingMcpTools(InvoiceQueryUseCase queries, DocumentAccess access, DocumentStoreResolver stores,
                             Clock clock) {
        this.queries = queries;
        this.access = access;
        this.stores = stores;
        this.clock = clock;
    }

    // ── unpaid_invoices ───────────────────────────────────────────────────────────────────────────────────

    public record UnpaidInvoice(String number, String client, String clientPhone, LocalDate issueDate,
                                LocalDate dueDate, long total, long paid, long due, String state, String store) {
    }

    public record UnpaidInvoices(long totalInvoices, int page, int size, long dueOnThisPage,
                                 List<UnpaidInvoice> invoices) {
    }

    @Tool(name = "unpaid_invoices", description = "Factures non soldées (impayées, partiellement payées ou en retard), "
            + "les plus récentes d'abord, par pages : numéro, client et téléphone, dates d'émission et d'échéance, "
            + "total, déjà payé, reste dû, état. Filtres : recherche (numéro ou nom du client), en retard seulement, "
            + "boutique (propriétaire seulement). Un employé ne voit que ses factures, un gérant celles de sa "
            + "boutique. Pour le nombre de factures non soldées, le total dû ou qui relancer, utiliser plutôt "
            + "unpaid_call_list. Montants en FCFA.")
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    public UnpaidInvoices unpaidInvoices(
            @ToolParam(required = false, description = "Partie du numéro ou du nom du client") String search,
            @ToolParam(required = false, description = "true = seulement les factures en retard") Boolean overdueOnly,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), "
                    + "propriétaire seulement") UUID storeId,
            @ToolParam(required = false, description = "Numéro de page, à partir de 0") Integer page,
            @ToolParam(required = false, description = "Factures par page, 1 à 50 (défaut 20)") Integer size) {
        int pageNumber = page == null ? 0 : Math.max(0, page);
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        Set<PaymentState> states = Boolean.TRUE.equals(overdueOnly) ? Set.of(PaymentState.OVERDUE) : OUTSTANDING;
        InvoiceRepository.Page result = queries.list(filter(search(search), states, access.forList(storeId), storeId),
                pageNumber, pageSize);
        List<Invoice> items = result.items();
        Map<UUID, PaymentStateCalculator.Collection> collections =
                queries.collections(items, queries.credits(items)); // one batched query for the page
        Map<UUID, String> storeNames = stores.namesOf(items, TenantContext.getCurrentTenant());
        List<UnpaidInvoice> rows = new ArrayList<>(items.size());
        long due = 0;
        for (Invoice invoice : items) {
            UnpaidInvoice row = row(invoice, collections.get(invoice.id()), storeNames);
            due += row.due();
            rows.add(row);
        }
        return new UnpaidInvoices(result.totalElements(), pageNumber, pageSize, due, rows);
    }

    // ── unpaid_call_list ──────────────────────────────────────────────────────────────────────────────────

    public record ClientToCall(String client, String phone, int invoices, int overdueInvoices, long due,
                               LocalDate oldestDueDate) {
    }

    /** Totals over every client owing something; {@code clients}: the largest debts, at most {@code limit}. */
    public record CallList(long clientsOwing, long unpaidInvoices, long totalDue, List<ClientToCall> clients) {
    }

    @Tool(name = "unpaid_call_list", description = "Impayés et liste d'appel : combien les clients doivent au total "
            + "(totalDue), combien de clients doivent de l'argent, combien de factures sont non soldées "
            + "(unpaidInvoices) ; puis les clients qui doivent le plus, avec téléphone, nombre de factures non soldées "
            + "(dont en retard), montant restant dû et plus ancienne échéance, du plus gros montant au plus petit. "
            + "Même visibilité que les factures. Montants en FCFA.")
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    public CallList unpaidCallList(
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), "
                    + "propriétaire seulement") UUID storeId,
            @ToolParam(required = false, description = "Nombre de clients, 1 à 30 (défaut 15)") Integer limit) {
        int clients = limit == null ? DEFAULT_CLIENTS : Math.max(1, Math.min(limit, MAX_CLIENTS));
        // one set-based statement over every outstanding invoice of the scope (no sample, no per-row query)
        InvoiceRepository.OutstandingByClient owed = queries.outstandingByClient(access.forList(storeId), storeId,
                clients);
        return new CallList(owed.clientCount(), owed.invoiceCount(), owed.totalDue(), owed.clients().stream()
                .map(c -> new ClientToCall(c.name() == null || c.name().isBlank() ? NO_CLIENT : c.name(), c.phone(),
                        c.invoices(), c.overdueInvoices(), c.due(), c.oldestDate()))
                .toList());
    }

    // ── invoice_collections ───────────────────────────────────────────────────────────────────────────────

    public record MethodCollected(String method, long payments, long amount) {
    }

    public record Collections(LocalDate from, LocalDate to, long payments, long amount,
                              List<MethodCollected> byMethod) {
    }

    @Tool(name = "invoice_collections", description = "Encaissements reçus sur les factures pendant une période (date "
            + "du paiement) : nombre de paiements, montant total et répartition par moyen de paiement. Les ventes "
            + "payées en caisse n'y sont pas (voir sales_history ou revenue_summary). Par défaut les 30 derniers jours. "
            + "Même visibilité que les factures. Montants en FCFA.")
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    public Collections invoiceCollections(
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ)") LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ), par défaut aujourd'hui")
            LocalDate to,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), "
                    + "propriétaire seulement") UUID storeId) {
        LocalDate dateTo = to != null ? to : LocalDate.now(clock.withZone(KeevoZones.WAT));
        LocalDate dateFrom = from != null ? from : dateTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        AiToolArguments.checkPeriod(dateFrom, dateTo);
        InvoicePaymentRepository.Collected c = queries.collected(dateFrom, dateTo, access.forList(storeId), storeId);
        List<MethodCollected> methods = new ArrayList<>(c.byMethod().stream()
                .map(m -> new MethodCollected(m.method() == null || m.method().isBlank() ? NO_METHOD : m.method(),
                        m.count(), m.amount()))
                .toList());
        if (c.otherCount() > 0) {
            methods.add(new MethodCollected(OTHER_METHODS, c.otherCount(), c.otherAmount())); // the parts add up
        }
        return new Collections(dateFrom, dateTo, c.count(), c.amount(), methods);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private static InvoiceRepository.Filter filter(String search, Set<PaymentState> states, DocumentScope scope,
                                                   UUID storeId) {
        return new InvoiceRepository.Filter(null, null, search, null, null, InvoiceKind.INVOICE, null, scope, storeId,
                states);
    }

    /** Same checks as the REST list: trimmed, at most 100 characters, no NUL. */
    static String search(String search) {
        String text = search == null || search.isBlank() ? null : search.strip();
        if (text != null && text.indexOf('\0') >= 0) {
            throw new InvalidRequestParameterException("search", "NUL character in search",
                    "La recherche contient un caractère non autorisé");
        }
        if (text != null && text.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidRequestParameterException("search", "search too long",
                    "La recherche ne doit pas dépasser " + MAX_SEARCH_LENGTH + " caractères");
        }
        return text;
    }

    private static UnpaidInvoice row(Invoice invoice, PaymentStateCalculator.Collection c,
                                     Map<UUID, String> storeNames) {
        long total = invoice.totals() == null ? 0 : invoice.totals().totalTtc();
        return new UnpaidInvoice(invoice.number(), clientName(invoice), clientPhone(invoice), invoice.issueDate(),
                dueDate(invoice), total, c == null ? 0 : c.amountPaid(), remaining(c),
                c == null ? null : c.stateLabel(),
                invoice.storeId() == null ? null : storeNames.get(invoice.storeId()));
    }

    /** What is left to pay: the amount due (credit notes deducted) minus what was received, never negative. */
    static long remaining(PaymentStateCalculator.Collection c) {
        return c == null ? 0 : Math.max(0, c.amountDue() - c.amountPaid());
    }

    private static String clientName(Invoice invoice) {
        String name = invoice.client() == null ? null : invoice.client().name();
        return name == null || name.isBlank() ? NO_CLIENT : name;
    }

    private static String clientPhone(Invoice invoice) {
        return invoice.client() == null ? null : invoice.client().phone();
    }

    private static LocalDate dueDate(Invoice invoice) {
        return invoice.terms() == null ? null : invoice.terms().dueDate();
    }
}
