package com.keevo.assistant.guided.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.guided.domain.model.AssistantPeriod;
import com.keevo.assistant.guided.domain.model.GuidedAnswer;
import com.keevo.assistant.guided.domain.model.GuidedAnswer.Row;
import com.keevo.assistant.guided.domain.model.GuidedAnswer.Stat;
import com.keevo.assistant.guided.domain.model.GuidedTopic;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.4 — Bref Point's guided topics, answered for free (no model, no AI credit) by the AI tools of 21.8 through
 * {@link AiToolRegistry} (channel ASSISTANT, audited): the role, permission, module and store / employee scope of each
 * tool apply exactly as on REST.
 *
 * <p><b>Pattern:</b> table-driven topics ({@link GuidedTopic}: tools, accepted filters) and one builder per topic
 * reading the tools' JSON (the module depends on the tool contracts, not on the business modules). A topic is answered
 * from its allowed tools; a refused secondary tool only removes its figures; when no tool of the topic answers, the
 * main tool's refusal is returned (French message of the REST gate).
 *
 * <p><b>Cost:</b> one listing of the allowed tools (in memory after one module-state read) and one to three tool
 * calls, each a bounded use case of 21.8.
 */
@Service
public class GuidedTopicService {

    static final int OVERDUE_ROWS = 3;
    static final int DEBTOR_ROWS = 3;
    static final int CUSTOMER_ROWS = 10;
    static final int CALL_LIST_ROWS = 30;
    static final int SEARCH_ROWS = 5;
    static final int MAX_SEARCH = 100;

    private final AiToolRegistry tools;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GuidedTopicService(AiToolRegistry tools, ObjectMapper mapper, Clock clock) {
        this.tools = tools;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The topics this session can open (at least one of their tools allowed), in Bref's order. */
    public List<GuidedTopic> available() {
        Set<String> allowed = allowed();
        return java.util.Arrays.stream(GuidedTopic.values())
                .filter(t -> t.tools().stream().anyMatch(allowed::contains)).toList();
    }

    /**
     * Answers {@code topicId}.
     *
     * @param periodId {@code today}, {@code week}, {@code month} (default) or {@code year}, for topics taking one
     * @throws InvalidRequestParameterException unknown topic or period, a filter the topic does not take, an invalid
     *                                          argument refused by a tool
     * @throws DomainException                  the tool's refusal (FORBIDDEN, MODULE_…) when nothing of the topic is
     *                                          allowed; {@code AI_ANSWER_FAILED} when a tool fails
     */
    public GuidedAnswer answer(String topicId, String periodId, boolean compare, UUID storeId, String search) {
        GuidedTopic topic = GuidedTopic.fromId(topicId).orElseThrow(() -> new InvalidRequestParameterException(
                "topic", "unknown topic", "Sujet inconnu"));
        AssistantPeriod period = null;
        if (topic.takesPeriod()) {
            period = periodId == null || periodId.isBlank() ? AssistantPeriod.MONTH : AssistantPeriod.fromId(periodId)
                    .orElseThrow(() -> new InvalidRequestParameterException("period", "unknown period",
                            "Période inconnue : aujourd'hui, semaine, mois ou année"));
        } else if (topic == GuidedTopic.DIAGNOSTIC) {
            period = AssistantPeriod.MONTH; // Bref: the diagnostic of the current month
        }
        if (compare && !topic.takesCompare()) {
            throw new InvalidRequestParameterException("compare", "topic without comparison",
                    "La comparaison n'est pas proposée pour ce sujet");
        }
        if (storeId != null && !topic.takesStore()) {
            throw new InvalidRequestParameterException("storeId", "topic without store filter",
                    "Le filtre par boutique n'est pas proposé pour ce sujet");
        }
        String text = search == null || search.isBlank() ? null : search.strip();
        if (text != null && !topic.takesSearch()) {
            throw new InvalidRequestParameterException("search", "topic without search",
                    "La recherche n'est pas proposée pour ce sujet");
        }
        if (text != null && (text.length() > MAX_SEARCH || text.indexOf('\0') >= 0)) {
            throw new InvalidRequestParameterException("search", "invalid search",
                    "La recherche ne doit pas dépasser " + MAX_SEARCH + " caractères");
        }
        Calls calls = new Calls(allowed());
        LocalDate today = LocalDate.now(clock.withZone(KeevoZones.WAT));
        GuidedAnswer.Period p = period == null ? null : period(period, period.current(today));
        Builder out = new Builder(topic, p);
        switch (topic) {
            case DIAGNOSTIC -> diagnostic(calls, out, period.current(today));
            case REVENUE -> revenue(calls, out, period, today, compare, storeId);
            case OVERDUE -> overdue(calls, out, storeId);
            case COLLECTIONS -> collections(calls, out, period, today, compare, storeId);
            case SELLERS -> sellers(calls, out, period.current(today), storeId);
            case SHOPS -> shops(calls, out, period.current(today));
            case CUSTOMERS -> customers(calls, out, storeId, text);
            case CALL_LIST -> callList(calls, out, storeId);
        }
        if (!calls.answered) {
            throw calls.refusal();
        }
        return out.build();
    }

    // ── topics ──────────────────────────────────────────────────────────────────────────────────────────

    private void diagnostic(Calls calls, Builder out, AssistantPeriod.Range range) {
        calls.optional("revenue_summary", args("from", range.from(), "to", range.to())).ifPresent(r -> {
            out.stat("revenue", "Chiffre d'affaires du mois", r.path("revenue").asLong(), "FCFA");
            out.stat("grossMargin", "Marge brute", r.path("grossMargin").asLong(), "FCFA");
        });
        calls.optional("invoice_collections", args("from", range.from(), "to", range.to())).ifPresent(c ->
                out.stat("collected", "Encaissé sur les factures", c.path("amount").asLong(), "FCFA"));
        calls.optional("unpaid_call_list", args("limit", DEBTOR_ROWS)).ifPresent(u -> {
            long unpaid = u.path("unpaidInvoices").asLong();
            out.stat("unpaidInvoices", "Factures non soldées", unpaid, "COUNT");
            out.stat("totalDue", "Montant restant dû", u.path("totalDue").asLong(), "FCFA");
            out.stat("clientsOwing", "Clients débiteurs", u.path("clientsOwing").asLong(), "COUNT");
            debtorRows(out, u);
            out.text = unpaid > 0 ? "Voici l'état de votre activité ce mois. " + unpaid + (unpaid > 1
                    ? " factures non soldées nécessitent votre attention." : " facture non soldée nécessite votre attention.")
                    : "Voici l'état de votre activité ce mois. Aucune facture non soldée : tout est à jour.";
        });
        if (out.text == null) {
            out.text = "Voici l'état de votre activité ce mois.";
        }
    }

    private void revenue(Calls calls, Builder out, AssistantPeriod period, LocalDate today, boolean compare,
                         UUID storeId) {
        AssistantPeriod.Range cur = period.current(today);
        if (compare) {
            AssistantPeriod.Range prev = period.previous(today);
            calls.optional("compare_revenue_periods", args("firstFrom", prev.from(), "firstTo", prev.to(),
                    "secondFrom", cur.from(), "secondTo", cur.to(), "storeId", storeId)).ifPresent(c -> {
                out.title = "Comparaison — " + period.label() + " et " + period.previousLabel();
                out.stat("revenue", "Chiffre d'affaires (" + period.label() + ")", c.path("second").path("revenue")
                        .asLong(), "FCFA");
                out.stat("previousRevenue", "Chiffre d'affaires (" + period.previousLabel() + ")",
                        c.path("first").path("revenue").asLong(), "FCFA");
                out.stat("difference", "Écart", c.path("revenueDifference").asLong(), "FCFA");
                if (c.hasNonNull("revenueChangePercent")) {
                    out.stat("changePercent", "Évolution", c.path("revenueChangePercent").asDouble(), "PERCENT");
                }
            });
            return;
        }
        calls.optional("revenue_summary", args("from", cur.from(), "to", cur.to(), "storeId", storeId)).ifPresent(r -> {
            out.title = "Chiffre d'affaires — " + period.label();
            out.stat("revenue", "Chiffre d'affaires", r.path("revenue").asLong(), "FCFA");
            out.stat("grossMargin", "Marge brute", r.path("grossMargin").asLong(), "FCFA");
            out.stat("marginPercent", "Taux de marge sur le coût", r.path("marginPercentOnCost").asDouble(), "PERCENT");
            out.stat("revenueWithUnknownMargin", "Ventes sans coût connu", r.path("revenueWithUnknownMargin").asLong(),
                    "FCFA");
        });
    }

    private void overdue(Calls calls, Builder out, UUID storeId) {
        calls.optional("unpaid_invoices", args("overdueOnly", true, "size", OVERDUE_ROWS, "storeId", storeId))
                .ifPresent(u -> {
                    out.stat("overdueInvoices", "Factures en retard", u.path("totalInvoices").asLong(), "COUNT");
                    for (JsonNode i : u.path("invoices")) {
                        out.row(i.path("client").asText(), i.path("due").asLong(), "Facture " + i.path("number")
                                .asText("") + dueText(i), text(i, "clientPhone"));
                    }
                });
        calls.optional("unpaid_call_list", args("limit", 1, "storeId", storeId)).ifPresent(u -> {
            out.stat("unpaidInvoices", "Factures non soldées (en retard ou non)", u.path("unpaidInvoices").asLong(),
                    "COUNT");
            out.stat("totalDue", "Montant restant dû", u.path("totalDue").asLong(), "FCFA");
        });
    }

    private void collections(Calls calls, Builder out, AssistantPeriod period, LocalDate today, boolean compare,
                             UUID storeId) {
        AssistantPeriod.Range cur = period.current(today);
        calls.optional("invoice_collections", args("from", cur.from(), "to", cur.to(), "storeId", storeId))
                .ifPresent(c -> {
                    out.stat("collected", "Encaissé", c.path("amount").asLong(), "FCFA");
                    out.stat("payments", "Paiements reçus", c.path("payments").asLong(), "COUNT");
                    for (JsonNode m : c.path("byMethod")) {
                        long n = m.path("payments").asLong();
                        out.row(m.path("method").asText(), m.path("amount").asLong(),
                                n + (n > 1 ? " paiements" : " paiement"), null);
                    }
                    if (compare) {
                        AssistantPeriod.Range prev = period.previous(today);
                        calls.optional("invoice_collections", args("from", prev.from(), "to", prev.to(), "storeId",
                                storeId)).ifPresent(p -> {
                            long now = c.path("amount").asLong();
                            long before = p.path("amount").asLong();
                            out.title = "Encaissements — " + period.label() + " et " + period.previousLabel();
                            out.stat("previousCollected", "Encaissé (" + period.previousLabel() + ")", before, "FCFA");
                            out.stat("difference", "Écart", now - before, "FCFA");
                            if (before > 0) {
                                out.stat("changePercent", "Évolution",
                                        Math.round((now - before) * 1000.0 / before) / 10.0, "PERCENT");
                            }
                        });
                    }
                });
        calls.optional("unpaid_call_list", args("limit", 1, "storeId", storeId)).ifPresent(u ->
                out.stat("totalDue", "Restant dû sur les factures", u.path("totalDue").asLong(), "FCFA"));
    }

    private void sellers(Calls calls, Builder out, AssistantPeriod.Range range, UUID storeId) {
        calls.optional("seller_ranking", args("from", range.from(), "to", range.to(), "storeId", storeId))
                .ifPresent(s -> {
                    long total = 0;
                    int count = 0;
                    for (JsonNode r : s.path("sellers")) {
                        count++;
                        total += r.path("revenue").asLong();
                        String name = r.path("seller").asText() + (r.path("me").asBoolean() ? " (vous)" : "");
                        long sales = r.path("salesCount").asLong();
                        out.row(name, r.path("revenue").asLong(), sales + (sales > 1 ? " ventes" : " vente")
                                + " · panier moyen " + fcfa(r.path("averageBasket").asLong()), null);
                    }
                    out.stat("sellers", "Vendeurs classés", count, "COUNT");
                    out.stat("revenue", "Chiffre d'affaires des vendeurs", total, "FCFA");
                    if (s.path("truncated").asBoolean()) {
                        out.text = "Classement limité aux " + count + " premiers vendeurs.";
                    }
                });
    }

    private void shops(Calls calls, Builder out, AssistantPeriod.Range range) {
        calls.optional("store_performance", args("from", range.from(), "to", range.to())).ifPresent(s -> {
            long total = 0;
            int count = 0;
            for (JsonNode r : s) {
                count++;
                total += r.path("revenue").asLong();
                long sales = r.path("salesCount").asLong();
                String detail = sales + (sales > 1 ? " ventes" : " vente") + " · panier moyen "
                        + fcfa(r.path("averageBasket").asLong());
                if (r.hasNonNull("changePercentVsPreviousPeriod")) {
                    detail += " · " + percent(r.path("changePercentVsPreviousPeriod").asDouble());
                }
                out.row(r.path("store").asText(), r.path("revenue").asLong(), detail, null);
            }
            out.stat("stores", "Boutiques", count, "COUNT");
            out.stat("revenue", "Chiffre d'affaires total", total, "FCFA");
        });
    }

    private void customers(Calls calls, Builder out, UUID storeId, String search) {
        if (search != null) {
            calls.optional("unpaid_invoices", args("search", search, "size", SEARCH_ROWS, "storeId", storeId))
                    .ifPresent(u -> {
                        out.title = "Client « " + search + " »";
                        out.stat("unpaidInvoices", "Factures non soldées", u.path("totalInvoices").asLong(), "COUNT");
                        for (JsonNode i : u.path("invoices")) {
                            out.row(i.path("client").asText(), i.path("due").asLong(), "Facture " + i.path("number")
                                    .asText("") + " · " + i.path("state").asText("") + dueText(i),
                                    text(i, "clientPhone"));
                        }
                    });
            return;
        }
        calls.optional("unpaid_call_list", args("limit", CUSTOMER_ROWS, "storeId", storeId)).ifPresent(u -> {
            out.stat("clientsOwing", "Clients débiteurs", u.path("clientsOwing").asLong(), "COUNT");
            out.stat("totalDue", "Montant restant dû", u.path("totalDue").asLong(), "FCFA");
            debtorRows(out, u);
        });
    }

    private void callList(Calls calls, Builder out, UUID storeId) {
        calls.optional("unpaid_call_list", args("limit", CALL_LIST_ROWS, "storeId", storeId)).ifPresent(u -> {
            int withPhone = 0;
            for (JsonNode c : u.path("clients")) {
                if (text(c, "phone") != null) {
                    withPhone++;
                }
            }
            out.stat("clientsToCall", "Clients à relancer", withPhone, "COUNT");
            out.stat("totalDue", "Montant restant dû", u.path("totalDue").asLong(), "FCFA");
            debtorRows(out, u);
            out.text = withPhone > 0 ? withPhone + (withPhone > 1 ? " clients à relancer :" : " client à relancer :")
                    : "Aucun numéro de téléphone parmi les clients débiteurs.";
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private static void debtorRows(Builder out, JsonNode callList) {
        for (JsonNode c : callList.path("clients")) {
            long n = c.path("invoices").asLong();
            long late = c.path("overdueInvoices").asLong();
            String detail = n + (n > 1 ? " factures" : " facture") + (late > 0 ? " dont " + late + " en retard" : "");
            out.row(c.path("client").asText(), c.path("due").asLong(), detail, text(c, "phone"));
        }
    }

    /** « 12 500 FCFA ». */
    static String fcfa(long amount) {
        java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        return new java.text.DecimalFormat("#,##0", symbols).format(amount) + " FCFA";
    }

    /** « +12,3 % », « -4 % ». */
    static String percent(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        String text = rounded == Math.rint(rounded) ? String.valueOf((long) rounded)
                : String.valueOf(rounded).replace('.', ',');
        return (rounded >= 0 ? "+" : "") + text + " %";
    }

    private static String dueText(JsonNode invoice) {
        String due = text(invoice, "dueDate");
        return due == null ? "" : " · échéance " + frenchDate(due);
    }

    /** {@code 2026-10-05} → {@code 05/10/2026}. */
    static String frenchDate(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static GuidedAnswer.Period period(AssistantPeriod p, AssistantPeriod.Range r) {
        return new GuidedAnswer.Period(p.id(), p.label(), r.from(), r.to());
    }

    /** Arguments as an ordered map; {@code null} values left out; dates as ISO strings. */
    static Map<String, Object> args(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v != null) {
                m.put((String) pairs[i], v instanceof LocalDate || v instanceof UUID ? v.toString() : v);
            }
        }
        return m;
    }

    private Set<String> allowed() {
        Set<String> names = new HashSet<>();
        for (AiToolDescriptor d : tools.allowedTools()) {
            names.add(d.name());
        }
        return names;
    }

    /** Calls the tools of one answer, remembers whether one answered and the first refusal. */
    private final class Calls {

        private final Set<String> allowed;
        private boolean answered;
        private AiToolResult refused;
        private String notAllowed;

        Calls(Set<String> allowed) {
            this.allowed = allowed;
        }

        Optional<JsonNode> optional(String tool, Map<String, Object> args) {
            if (!allowed.contains(tool)) {
                if (notAllowed == null) {
                    notAllowed = tool;
                }
                return Optional.empty();
            }
            AiToolResult r = invoke(tool, args);
            switch (r.outcome()) {
                case OK -> {
                    answered = true;
                    try {
                        return Optional.of(mapper.readTree(r.json()));
                    } catch (Exception e) {
                        throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "unreadable tool result");
                    }
                }
                case REFUSED -> {
                    if (refused == null) {
                        refused = r;
                    }
                    return Optional.empty();
                }
                case INVALID -> throw new InvalidRequestParameterException("topic", "tool refused the arguments",
                        r.message());
                default -> throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "tool " + tool + " failed");
            }
        }

        private AiToolResult invoke(String tool, Map<String, Object> args) {
            try {
                return tools.call(tool, mapper.writeValueAsString(args), AiToolChannel.ASSISTANT);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "arguments not serialisable");
            }
        }

        /**
         * The refusal to return: the first refusal met, else that of the first needed tool the session may not call
         * (called once, refused by its gate before anything runs, for its exact French message).
         */
        DomainException refusal() {
            AiToolResult r = refused;
            if (r == null && notAllowed != null) {
                r = invoke(notAllowed, Map.of());
            }
            if (r == null) {
                return DomainException.userFacing(ErrorCode.FORBIDDEN, "Ce sujet n'est pas disponible pour vous.");
            }
            if (r.isOk()) {
                return DomainException.userFacing(ErrorCode.FORBIDDEN, "Ce sujet n'est pas disponible pour vous.");
            }
            ErrorCode code;
            try {
                code = ErrorCode.valueOf(r.errorCode());
            } catch (RuntimeException e) {
                code = ErrorCode.FORBIDDEN;
            }
            if (r.outcome() == AiToolResult.Outcome.ERROR) {
                return new DomainException(ErrorCode.AI_ANSWER_FAILED, "tool failed");
            }
            return DomainException.userFacing(code, r.message());
        }
    }

    /** Collects the answer of one topic. */
    private static final class Builder {

        private final GuidedTopic topic;
        private final GuidedAnswer.Period period;
        private final List<Stat> stats = new ArrayList<>();
        private final List<Row> rows = new ArrayList<>();
        private String title;
        private String text;

        Builder(GuidedTopic topic, GuidedAnswer.Period period) {
            this.topic = topic;
            this.period = period;
        }

        void stat(String key, String label, Number value, String unit) {
            stats.add(new Stat(key, label, value, unit));
        }

        void row(String label, Long amount, String detail, String phone) {
            rows.add(new Row(label, amount, detail, phone));
        }

        GuidedAnswer build() {
            String t = title != null ? title : topic.label() + (period != null ? " — " + period.label() : "");
            return new GuidedAnswer(topic.id(), t, text, period, stats, rows);
        }
    }
}
