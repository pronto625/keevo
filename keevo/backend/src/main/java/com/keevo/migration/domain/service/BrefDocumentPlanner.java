package com.keevo.migration.domain.service;

import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.invoicing.api.InvoiceImportPort.Seller;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.shared.domain.time.KeevoZones;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 24.4 — the plan of an owner's Bref documents (pure, deterministic, no I/O): every Bref sale or quote becomes a
 * Keevo document with its lines and payments, as Bref had them (R3: a Bref value is never corrected — an amount with
 * cents is rounded to the franc Keevo stores and noted with its exact value, a total that differs from its recalcul
 * is stored as Bref had it and noted). Mapping rules: spec 24.4 (Boundaries, D1–D8).
 *
 * <p>Cost: one sort of the sales for the numbering (O(n log n)), every other lookup by hash map (O(n) over documents,
 * lines and payments).
 */
public final class BrefDocumentPlanner {

    /** {@code migration_records.note} is {@code VARCHAR(300)}. */
    public static final int MAX_NOTE = 300;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999");
    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final LocalTime PAID_TIME = LocalTime.NOON;
    static final String DEFAULT_CANCELLATION = "Annulée dans Bref";
    static final String DEFAULT_CLIENT = "Client";
    static final String DEFAULT_LINE = "Article";
    private static final Pattern CIVIL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Map<String, String> METHODS = Map.of(
            "cash", "Espèces",
            "mobile_money", "Mobile Money",
            "bank_transfer", "Virement",
            "card", "Carte",
            "other", "Autre");

    private BrefDocumentPlanner() {
    }

    /**
     * Keevo ids of what the documents point to, built by the caller from the snapshot and the records of 24.3.
     *
     * @param products Keevo product id by {@link #shopKey}{@code (shop, Bref productKey)}
     * @param clients  Keevo client id by {@link #shopKey}{@code (shop, phone key)} ({@link #phoneKey})
     * @param accounts Story 24.7 — the Keevo account of a Bref user ({@code ImportRun.accountId}: the Bref id when the
     *                 import created it, the existing account it was linked to, {@code null} when not imported or
     *                 only invited); a Bref user id is never stored as is
     */
    public record Lookup(Map<String, UUID> products, Map<String, UUID> clients,
                         java.util.function.Function<UUID, UUID> accounts) {

        /** Without account mapping (every Bref user id is its Keevo account) — tests. */
        public Lookup(Map<String, UUID> products, Map<String, UUID> clients) {
            this(products, clients, id -> id);
        }
    }

    /**
     * The Keevo author of a Bref user reference (D10): its Keevo account, else the owner's account — noted when a
     * given author has no Keevo account (member not imported, or only invited).
     */
    static UUID author(String brefUserId, UUID ownerAccount, Lookup lookup, List<Kind> notes, Kind replaced) {
        UUID bref = BrefMappings.uuidOrNull(brefUserId);
        UUID account = bref == null ? null : lookup.accounts().apply(bref);
        if (account == null) {
            if (BrefMappings.trimmed(brefUserId) != null && !notes.contains(replaced)) {
                notes.add(replaced);
            }
            return ownerAccount;
        }
        return account;
    }

    /** A planned Keevo row and the Bref row it comes from (record: source table and canonical form). */
    public record Row<T>(T row, String sourceTable, UUID sourceId, String canonical, List<Kind> notes, String note) {
    }

    /** A planned document with its own notes, its lines and its payments (the acompte included). */
    public record PlannedDocument(BrefInvoice source, Document document, List<Kind> notes, String note,
                                  List<Row<Line>> lines, List<Row<Payment>> payments) {
    }

    /** The plan: quotes first, then invoices (each by creation), and the seller identity of the tenant settings. */
    public record Plan(BrefShop settingsShop, Seller settings, List<PlannedDocument> documents) {
    }

    /** {@code shopId|key}, the shop id trimmed and lower-cased (Bref keeps it as text). */
    public static String shopKey(String shopId, String key) {
        UUID shop = BrefMappings.uuidOrNull(shopId);
        return (shop == null ? "?" : shop.toString()) + "|" + key;
    }

    /** The phone a document and a customer are matched on: E.164 when readable, else the trimmed text. */
    public static String phoneKey(String phone) {
        String e164 = BrefMappings.e164(phone);
        return e164 != null ? e164 : BrefMappings.trimmed(phone);
    }

    /** The id of the acompte payment row of a Bref invoice (stable across runs: name-based). */
    public static UUID acompteId(UUID invoiceId) {
        return UUID.nameUUIDFromBytes(("bref:initialPayment:" + invoiceId).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code BRF-} and the first 8 characters of the Bref id, upper-case (Bref's printed reference). */
    public static String legacyReference(UUID id) {
        return "BRF-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** The shop whose identity heads the tenant settings: the oldest live shop, else the oldest. */
    public static BrefShop settingsShop(List<BrefShop> shops) {
        return shops.stream().sorted(Comparator.comparing(BrefShop::deleted).thenComparing(BrefShop::createdAt)
                .thenComparing(BrefShop::id)).findFirst().orElse(null);
    }

    public static Plan plan(List<BrefShop> shops, List<BrefInvoice> invoices, UUID ownerAccount, Lookup lookup) {
        Map<UUID, BrefShop> shopById = new HashMap<>();
        shops.forEach(s -> shopById.put(s.id(), s));
        BrefShop first = settingsShop(shops);
        Seller settings = first == null ? null : seller(first, new ArrayList<>());
        Map<UUID, String> numbers = numbers(invoices);
        List<PlannedDocument> planned = new ArrayList<>(invoices.size());
        invoices.stream()
                .sorted(Comparator.comparing((BrefInvoice i) -> !i.quote()).thenComparing(BrefInvoice::createdAt)
                        .thenComparing(BrefInvoice::id))
                .forEach(i -> planned.add(document(i, shopById, numbers.get(i.id()), ownerAccount, lookup)));
        return new Plan(first, settings, planned);
    }

    /** {@code BRF-XXXXXXXX} per sale, oldest first; a prefix already used gets {@code -2}, {@code -3}… */
    static Map<UUID, String> numbers(List<BrefInvoice> invoices) {
        Map<String, Integer> used = new HashMap<>();
        Map<UUID, String> numbers = new HashMap<>();
        invoices.stream().filter(i -> !i.quote())
                .sorted(Comparator.comparing(BrefInvoice::createdAt).thenComparing(BrefInvoice::id))
                .forEach(i -> {
                    String base = legacyReference(i.id());
                    int n = used.merge(base, 1, Integer::sum);
                    numbers.put(i.id(), n == 1 ? base : base + "-" + n);
                });
        return numbers;
    }

    private static PlannedDocument document(BrefInvoice i, Map<UUID, BrefShop> shops, String number, UUID ownerId,
                                            Lookup lookup) {
        List<Kind> notes = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean quote = i.quote();
        UUID storeId = BrefMappings.uuidOrNull(i.shopId());
        BrefShop shop = storeId == null ? null : shops.get(storeId);

        // amounts (D1): rounded to the franc, the Bref value noted when it had cents
        long subtotal = franc(i.subtotal(), "sous-total", details);
        long discount = franc(i.discount(), "remise", details);
        long delivery = franc(i.deliveryFee(), "livraison", details);
        long tax = franc(i.tax(), "TVA", details);
        long total = franc(i.total(), "total", details);
        if (!details.isEmpty()) {
            notes.add(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
        }
        List<String> mismatch = new ArrayList<>();
        BigDecimal recalc = i.subtotal().subtract(i.discount()).add(i.tax()).add(i.deliveryFee());
        if (i.total().subtract(recalc).abs().compareTo(ONE) > 0) {
            mismatch.add("total Bref " + plain(i.total()) + ", recalcul " + plain(recalc));
        }
        BigDecimal lines = i.items().stream().map(BrefInvoiceItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!i.items().isEmpty() && i.subtotal().subtract(lines).abs().compareTo(ONE) > 0) {
            mismatch.add("sous-total Bref " + plain(i.subtotal()) + ", somme des lignes " + plain(lines));
        }
        BigDecimal base = i.subtotal().subtract(i.discount());
        BigDecimal rate = BigDecimal.ZERO.setScale(2);
        if (i.tax().signum() > 0 && base.signum() <= 0) {
            mismatch.add("TVA Bref " + plain(i.tax()) + " sans base taxable");
        } else if (i.tax().signum() > 0) {
            rate = i.tax().multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            if (rate.compareTo(HUNDRED) > 0) {
                mismatch.add("taux de TVA " + plain(rate) + " % ramené à 100 %");
                rate = HUNDRED.setScale(2);
            }
        }
        if (!mismatch.isEmpty()) {
            notes.add(Kind.DOCUMENT_TOTAL_MISMATCH);
            details.addAll(mismatch);
        }

        String status;
        if (quote) {
            status = i.cancelled() ? "CANCELLED" : "DRAFT";
        } else if (i.cancelled()) {
            status = "CANCELLED";
        } else {
            status = i.paid() ? "PAID" : "ISSUED";
        }
        LocalDate issueDate = null;
        Instant issuedAt = null;
        if (!quote) {
            issueDate = civilDate(i.date());
            if (issueDate == null) {
                issueDate = LocalDate.ofInstant(i.createdAt(), KeevoZones.WAT);
                notes.add(Kind.DOCUMENT_DATE_INVALID);
            }
            issuedAt = i.validatedAt() != null ? i.validatedAt() : i.createdAt();
        }
        if (i.deleted()) {
            notes.add(Kind.DOCUMENT_DELETED);
        }
        String method = method(i.paymentMethod());
        UUID createdBy = author(i.userId(), ownerId, lookup, notes, Kind.AUTHOR_REPLACED);

        // payments (D3): the acompte as one paid row, then Bref's instalments
        List<Row<Payment>> payments = new ArrayList<>();
        LocalDate fallbackDue = issueDate != null ? issueDate : LocalDate.ofInstant(i.createdAt(), KeevoZones.WAT);
        BigDecimal collected = BigDecimal.ZERO;
        if (quote && i.initialPayment().signum() > 0) {
            // Bref never takes an acompte on a quote: one there is an inconsistency, kept in the note (R3)
            notes.add(Kind.PAYMENT_ON_QUOTE);
            details.add("acompte sur devis " + plain(i.initialPayment()));
        }
        if (!quote && i.initialPayment().signum() > 0) {
            List<String> acompteDetails = new ArrayList<>();
            long amount = franc(i.initialPayment(), "acompte", acompteDetails);
            collected = collected.add(i.initialPayment());
            List<Kind> rowNotes = new ArrayList<>();
            if (!acompteDetails.isEmpty()) {
                rowNotes.add(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
            }
            if (amount == 0) {
                addOnce(notes, Kind.PAYMENT_ZERO_SKIPPED);
            } else {
                Instant at = i.validatedAt() != null ? i.validatedAt() : i.createdAt();
                payments.add(new Row<>(new Payment(acompteId(i.id()), i.id(), amount, null, at, method, null,
                        createdBy, i.createdAt(), i.updatedAt()), MigrationRecord
                        .BREF_INVOICE, i.id(), i.canonical(), rowNotes, note(rowNotes, acompteDetails)));
            }
        }
        for (BrefInvoicePayment p : i.payments()) {
            List<Kind> rowNotes = new ArrayList<>();
            List<String> rowDetails = new ArrayList<>();
            long amount = franc(p.amount(), "paiement", rowDetails);
            if (!rowDetails.isEmpty()) {
                rowNotes.add(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
            }
            if (p.paid()) {
                collected = collected.add(p.amount());
            }
            if (amount == 0) {
                addOnce(notes, Kind.PAYMENT_ZERO_SKIPPED);
                continue;
            }
            if (quote) {
                rowNotes.add(Kind.PAYMENT_ON_QUOTE);
            }
            LocalDate due = civilDate(p.dueDate());
            Instant paidAt = null;
            if (p.paid()) {
                LocalDate paidOn = civilDate(p.paidDate());
                if (paidOn == null) {
                    paidAt = p.updatedAt();
                    rowNotes.add(Kind.PAYMENT_DATE_INVALID);
                } else {
                    paidAt = paidOn.atTime(PAID_TIME).atZone(KeevoZones.WAT).toInstant();
                }
            } else if (due == null) {
                due = fallbackDue;
                rowNotes.add(Kind.PAYMENT_DATE_INVALID);
            }
            String paymentNote = BrefMappings.trimmed(p.notes());
            if (paymentNote != null && length(paymentNote) > 500) {
                paymentNote = BrefMappings.cut(paymentNote, 500);
                rowNotes.add(Kind.DOCUMENT_FIELD_SHORTENED);
            }
            payments.add(new Row<>(new Payment(p.id(), i.id(), amount, due, paidAt, method, paymentNote, createdBy,
                    p.createdAt(), p.updatedAt()), MigrationRecord
                    .BREF_INVOICE_PAYMENT, p.id(), p.canonical(), rowNotes, note(rowNotes, rowDetails)));
        }
        if (!quote && !i.cancelled()) {
            if (i.paid() && collected.compareTo(i.total()) < 0) {
                notes.add(Kind.DOCUMENT_PAID_NOT_COLLECTED);
                details.add("encaissé " + plain(collected) + " sur " + plain(i.total()));
            } else if (!i.paid() && i.total().signum() > 0 && collected.compareTo(i.total()) >= 0) {
                notes.add(Kind.DOCUMENT_STATE_DIFFERS);
            }
        }
        if (!quote && collected.compareTo(i.total()) > 0) {
            notes.add(Kind.DOCUMENT_OVERPAID);
            details.add("encaissé " + plain(collected) + " pour " + plain(i.total()));
        }
        Instant paidAt = null;
        if ("PAID".equals(status)) {
            paidAt = payments.stream().map(r -> r.row().paidAt()).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(i.updatedAt());
        }

        // lines: linked to the catalogue by the line's Bref product key in the document's shop
        BigDecimal percent = rate;
        List<Row<Line>> lineRows = new ArrayList<>();
        int position = 0;
        for (BrefInvoiceItem item : i.items()) {
            position++;
            List<Kind> rowNotes = new ArrayList<>();
            List<String> rowDetails = new ArrayList<>();
            String name = BrefMappings.trimmed(item.name());
            String description = name == null ? DEFAULT_LINE : name;
            if (name == null) {
                rowNotes.add(Kind.DOCUMENT_VALUE_MISSING);
            }
            String extra = BrefMappings.trimmed(item.description());
            if (extra != null && !extra.equals(name)) {
                description = description + " — " + extra;
            }
            if (length(description) > 500) {
                description = BrefMappings.cut(description, 500);
                addOnce(rowNotes, Kind.DOCUMENT_FIELD_SHORTENED);
            }
            String reference = BrefMappings.trimmed(item.reference());
            if (reference != null && length(reference) > 100) {
                reference = BrefMappings.cut(reference, 100);
                addOnce(rowNotes, Kind.DOCUMENT_FIELD_SHORTENED);
            }
            long unit = franc(item.unitPrice(), "prix unitaire", rowDetails);
            long ht = franc(item.total(), "total de ligne", rowDetails);
            if (!rowDetails.isEmpty()) {
                rowNotes.add(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
            }
            long vat = percent.signum() == 0 ? 0 : BigDecimal.valueOf(ht).multiply(percent)
                    .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
            String key = BrefProductKeys.key(item.name(), item.reference());
            UUID productId = key == null ? null : lookup.products().get(shopKey(i.shopId(), key));
            if (productId == null) {
                rowNotes.add(Kind.LINE_WITHOUT_PRODUCT);
            }
            lineRows.add(new Row<>(new Line(item.id(), i.id(), position, description, item.quantity(), unit, percent,
                    ht, vat, Math.addExact(ht, vat), productId, reference),
                    MigrationRecord.BREF_INVOICE_ITEM, item.id(), item.canonical(),
                    rowNotes, note(rowNotes, rowDetails)));
        }

        // client and seller (D4)
        boolean[] shortened = {false};
        String clientName = BrefMappings.trimmed(i.customerName());
        if (clientName == null) {
            clientName = DEFAULT_CLIENT;
            notes.add(Kind.DOCUMENT_VALUE_MISSING);
        }
        UUID token = i.deleted() ? null : BrefMappings.uuidOrNull(i.publicToken());
        if (!i.deleted() && token == null && BrefMappings.trimmed(i.publicToken()) != null) {
            notes.add(Kind.DOCUMENT_LINK_DROPPED);
        }
        clientName = cut(clientName, 200, shortened);
        String phoneKey = phoneKey(i.customerPhone());
        UUID clientId = phoneKey == null ? null : lookup.clients().get(shopKey(i.shopId(), phoneKey));
        String clientPhone = cut(phoneKey, 30, shortened);
        Seller seller = quote || shop == null ? null : seller(shop, notes);
        Document document = new Document(i.id(), quote, status, quote ? null : number, issueDate, clientId,
                clientName, clientPhone, cut(BrefMappings.trimmed(i.customerEmail()), 255, shortened),
                cut(BrefMappings.trimmed(i.customerNui()), 30, shortened),
                cut(BrefMappings.trimmed(i.customerRccm()), 50, shortened),
                cut(BrefMappings.trimmed(i.customerAddress()), 300, shortened),
                cut(BrefMappings.trimmed(i.customerContactName()), 120, shortened),
                cut(BrefMappings.trimmed(i.customerWebsite()), 200, shortened), seller, tax > 0, rate, subtotal,
                discount, delivery, subtotal - discount, tax, total, issuedAt, paidAt, method,
                "CANCELLED".equals(status) ? (i.cancelledAt() != null ? i.cancelledAt() : i.updatedAt()) : null,
                "CANCELLED".equals(status) ? cancellation(i.cancellationReason(), shortened) : null, createdBy,
                storeId, i.dueDate() == null ? null : LocalDate.ofInstant(i.dueDate(), ZoneOffset.UTC),
                token, i.publicTokenExpiresAt(),
                legacyReference(i.id()), BrefMappings.trimmed(i.notes()) == null ? null : i.notes(), i.createdAt(),
                i.updatedAt(), i.deletedAt());
        if (shortened[0]) {
            addOnce(notes, Kind.DOCUMENT_FIELD_SHORTENED);
        }
        if (!quote && number != null && !number.equals(legacyReference(i.id()))) {
            notes.add(Kind.DOCUMENT_NUMBER_SUFFIXED);
        }
        List<Kind> distinct = List.copyOf(new LinkedHashSet<>(notes));
        return new PlannedDocument(i, document, distinct, note(distinct, details), lineRows, payments);
    }

    /** The shop's identity as a document header (cut to the settings' limits; noted when cut). */
    private static Seller seller(BrefShop s, List<Kind> notes) {
        boolean[] shortened = {false};
        String phone = BrefMappings.e164(s.phone());
        if (phone == null) {
            phone = BrefMappings.trimmed(s.phone());
        }
        Seller seller = new Seller(cut(BrefMappings.trimmed(s.name()), 200, shortened),
                cut(BrefMappings.trimmed(s.address()), 300, shortened), cut(phone, 30, shortened),
                cut(BrefMappings.trimmed(s.niu()), 30, shortened), cut(BrefMappings.trimmed(s.rccm()), 60, shortened));
        if (shortened[0]) {
            addOnce(notes, Kind.DOCUMENT_FIELD_SHORTENED);
        }
        return seller;
    }

    private static String cancellation(String reason, boolean[] shortened) {
        String r = BrefMappings.trimmed(reason);
        return r == null ? DEFAULT_CANCELLATION : cut(r, 500, shortened);
    }

    /** Bref's payment method key as Keevo's French label; another non-blank value kept as text (≤ 60). */
    static String method(String key) {
        String k = BrefMappings.trimmed(key);
        if (k == null) {
            return null;
        }
        String label = METHODS.get(k.toLowerCase(Locale.ROOT));
        return label != null ? label : BrefMappings.cut(k, 60);
    }

    /** A strict civil date {@code YYYY-MM-DD} (trimmed); {@code null} when absent or invalid. */
    static LocalDate civilDate(String raw) {
        String v = BrefMappings.trimmed(raw);
        if (v == null || !CIVIL_DATE.matcher(v).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The franc Keevo stores (HALF_UP); a value with cents is listed in {@code details} with its Bref value. */
    static long franc(BigDecimal value, String what, List<String> details) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(value) != 0) {
            details.add(what + " Bref " + plain(value));
        }
        return rounded.longValueExact();
    }

    /** Whether an amount fits a Keevo amount column (0 – 999 999 999 999). */
    static boolean amountInRange(BigDecimal value) {
        return value.signum() >= 0 && value.compareTo(MAX_AMOUNT) <= 0;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String cut(String value, int max, boolean[] shortened) {
        if (value != null && length(value) > max) {
            shortened[0] = true;
            return BrefMappings.cut(value, max);
        }
        return value;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void addOnce(List<Kind> notes, Kind kind) {
        if (!notes.contains(kind)) {
            notes.add(kind);
        }
    }

    /**
     * The record's French note: the Bref values first (R3: what Keevo did not store as is must survive in the
     * record), then the labels; {@code null} when none; cut to {@link #MAX_NOTE}.
     */
    static String note(List<Kind> kinds, List<String> details) {
        if (kinds.isEmpty() && details.isEmpty()) {
            return null;
        }
        Set<String> parts = new LinkedHashSet<>();
        kinds.forEach(k -> parts.add(k.label()));
        String labels = String.join(" ; ", parts);
        String text = details.isEmpty() ? labels
                : "Valeurs Bref : " + String.join(", ", details) + (labels.isEmpty() ? "" : " — " + labels);
        return BrefMappings.cut(text, MAX_NOTE);
    }
}
