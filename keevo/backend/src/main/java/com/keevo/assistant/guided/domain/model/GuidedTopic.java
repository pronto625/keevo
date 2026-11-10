package com.keevo.assistant.guided.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Story 21.4 — the guided topics of Bref Point (chips of the chat), each served by AI tools of 21.8 — never by the
 * model, never counted. A topic is offered to a session when at least one of its tools is allowed (role, permission,
 * module); its answer is built from the allowed ones.
 */
public enum GuidedTopic {

    DIAGNOSTIC("diagnostic", "Diagnostic", List.of("revenue_summary", "invoice_collections", "unpaid_call_list"),
            false, false, false, false),
    REVENUE("revenue", "Chiffre d'affaires", List.of("revenue_summary", "compare_revenue_periods"), true, true, true,
            false),
    OVERDUE("overdue", "Factures en retard", List.of("unpaid_invoices", "unpaid_call_list"), false, true, false,
            false),
    COLLECTIONS("collections", "Encaissements", List.of("invoice_collections", "unpaid_call_list"), true, true, true,
            false),
    SELLERS("sellers", "Vendeurs", List.of("seller_ranking"), true, true, false, false),
    SHOPS("shops", "Boutiques", List.of("store_performance"), true, false, false, false),
    CUSTOMERS("customers", "Clients", List.of("unpaid_call_list", "unpaid_invoices"), false, true, false, true),
    CALL_LIST("call_list", "Liste d'appel des impayés", List.of("unpaid_call_list"), false, true, false, false);

    private final String id;
    private final String label;
    private final List<String> tools;
    private final boolean period;
    private final boolean store;
    private final boolean compare;
    private final boolean search;

    GuidedTopic(String id, String label, List<String> tools, boolean period, boolean store, boolean compare,
                boolean search) {
        this.id = id;
        this.label = label;
        this.tools = tools;
        this.period = period;
        this.store = store;
        this.compare = compare;
        this.search = search;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The AI tools feeding the topic, the main one first. */
    public List<String> tools() {
        return tools;
    }

    /** Takes a period (today, week, month, year; month by default). */
    public boolean takesPeriod() {
        return period;
    }

    /** Accepts a store filter ({@code storeId}, owner only — the tools refuse it to members). */
    public boolean takesStore() {
        return store;
    }

    /** Accepts {@code compare=true} (the period against Bref's previous period). */
    public boolean takesCompare() {
        return compare;
    }

    /** Accepts a client search (name or invoice number). */
    public boolean takesSearch() {
        return search;
    }

    public static Optional<GuidedTopic> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.id.equals(key)).findFirst();
    }
}
