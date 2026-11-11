package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.ImportNote.Kind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — the Keevo catalogue of a Bref owner, planned before any write (pure function, deterministic: the same
 * snapshot always gives the same plan, so a re-run and both sources agree).
 *
 * <ul>
 *   <li><b>Key groups</b> (decision D1): the live Bref products (not merged) of all the owner's shops grouped by
 *   {@code productKey} become ONE Keevo product, whose id, name and creation date are those of the oldest member
 *   ({@code createdAt}, then id); barcode = the first usable reference (oldest first); price = the most recent price
 *   typed by a person ({@code priceManual}), else the most recently sold one ({@code lastSoldAt}, then
 *   {@code updatedAt}); archived only when every member is; updated = the latest.</li>
 *   <li><b>Merged in Bref</b> (D2): a product with {@code mergedIntoId} gets its own archived row redirected (one hop)
 *   to the Keevo product of its final target's group; a missing, foreign or cyclic target leaves it unredirected.</li>
 *   <li><b>Names</b> (D3): unique case-insensitively across the tenant (Keevo {@code uq_products_name}): the live groups
 *   first, then the merged rows, oldest first; a taken name gets « (2) », « (3) »…; ≤ 200 characters; blank → «
 *   Produit importé ».</li>
 *   <li><b>Prices</b> (D4): integers, cents rounded half-up (noted); the validator blocks out-of-range prices.</li>
 * </ul>
 *
 * <p>Cost: O(n log n) (one sort), O(n) memory; merge chains are followed with a visited set (each product at most
 * once per chain, chains bounded by n).
 */
public final class BrefCatalogPlanner {

    public static final int MAX_NAME = 200;
    public static final int MAX_BARCODE = 100;
    public static final int MAX_KEY = 250;
    public static final String FALLBACK_NAME = "Produit importé";

    private static final Comparator<BrefProduct> OLDEST_FIRST =
            Comparator.comparing(BrefProduct::createdAt).thenComparing(BrefProduct::id);

    private BrefCatalogPlanner() {
    }

    /** A Keevo product to create (its id is a Bref product id). */
    public record PlannedProduct(UUID id, String name, String barcode, String productKey, int price,
                                 boolean priceManual, boolean archived, UUID mergedIntoId, Instant createdAt,
                                 Instant updatedAt) {
    }

    /** Where a Bref product goes: the Keevo product id, and the notes of that source row. */
    public record SourceLink(BrefProduct source, UUID targetId, List<Kind> notes) {
    }

    /** The plan: products to create, and one link per Bref product. */
    public record CatalogPlan(List<PlannedProduct> products, List<SourceLink> links) {
    }

    /** A plan for a tenant without products (first import). */
    public static CatalogPlan plan(List<BrefProduct> products) {
        return plan(products, Set.of(), Set.of());
    }

    /**
     * @param imported   ids of the Keevo products already imported (their rows exist: their names are not claimed again)
     * @param takenNames lower-cased names already used by the tenant's products (a re-run never gives a new product a
     *                   name a stored product holds — review 24.3: names stay stable when Bref grows between runs)
     */
    public static CatalogPlan plan(List<BrefProduct> products, Set<UUID> imported, Set<String> takenNames) {
        Map<UUID, BrefProduct> byId = new HashMap<>();
        products.forEach(p -> byId.putIfAbsent(p.id(), p));
        List<BrefProduct> sorted = byId.values().stream().sorted(OLDEST_FIRST).toList();

        Map<String, List<BrefProduct>> groups = new LinkedHashMap<>();
        List<BrefProduct> merged = new ArrayList<>();
        for (BrefProduct p : sorted) {
            if (BrefMappings.trimmed(p.mergedIntoId()) != null) {
                merged.add(p);
            } else {
                groups.computeIfAbsent(groupKey(p), k -> new ArrayList<>()).add(p); // sorted: oldest first
            }
        }
        Map<UUID, UUID> liveTarget = new HashMap<>();
        groups.values().forEach(g -> g.forEach(p -> liveTarget.put(p.id(), g.get(0).id())));

        Names names = new Names(takenNames);
        Map<UUID, List<Kind>> notes = new HashMap<>();
        List<PlannedProduct> planned = new ArrayList<>();
        for (List<BrefProduct> group : groups.values()) {
            BrefProduct rep = group.get(0);
            List<Kind> repNotes = notes.computeIfAbsent(rep.id(), k -> new ArrayList<>());
            BrefProduct priced = priceSource(group);
            String barcode = null;
            for (BrefProduct p : group) {
                String ref = BrefMappings.trimmed(p.reference());
                if (ref != null && ref.codePointCount(0, ref.length()) > MAX_BARCODE) {
                    notes.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(Kind.PRODUCT_REFERENCE_DROPPED);
                } else if (ref != null && barcode == null) {
                    barcode = ref;
                }
            }
            for (BrefProduct p : group.subList(1, group.size())) {
                notes.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(Kind.PRODUCT_MERGED_BY_KEY);
            }
            int price = price(priced, notes.computeIfAbsent(priced.id(), k -> new ArrayList<>()));
            Instant updated = group.stream().map(BrefProduct::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
            String name = imported.contains(rep.id()) ? stored(rep.name()) : names.claim(rep.name(), repNotes);
            planned.add(new PlannedProduct(rep.id(), name, barcode, key(rep), price,
                    priced.priceManual(), group.stream().allMatch(BrefProduct::archived), null, rep.createdAt(),
                    updated));
        }
        for (BrefProduct p : merged) {
            List<Kind> rowNotes = notes.computeIfAbsent(p.id(), k -> new ArrayList<>());
            UUID target = resolve(p, byId, liveTarget);
            rowNotes.add(target == null ? Kind.PRODUCT_MERGE_BROKEN : Kind.PRODUCT_MERGED_IN_BREF);
            String ref = BrefMappings.trimmed(p.reference());
            String barcode = ref;
            if (ref != null && ref.codePointCount(0, ref.length()) > MAX_BARCODE) {
                rowNotes.add(Kind.PRODUCT_REFERENCE_DROPPED);
                barcode = null;
            }
            int price = price(p, rowNotes);
            String name = imported.contains(p.id()) ? stored(p.name()) : names.claim(p.name(), rowNotes);
            planned.add(new PlannedProduct(p.id(), name, barcode, key(p), price,
                    p.priceManual(), true, target, p.createdAt(), p.updatedAt()));
        }

        List<SourceLink> links = new ArrayList<>(sorted.size());
        for (BrefProduct p : sorted) {
            UUID target = liveTarget.getOrDefault(p.id(), p.id());
            links.add(new SourceLink(p, target, List.copyOf(notes.getOrDefault(p.id(), List.of()))));
        }
        return new CatalogPlan(List.copyOf(planned), List.copyOf(links));
    }

    /** The price a Bref unit price gives in Keevo (rounded half-up), noting a rounding. */
    private static int price(BrefProduct p, List<Kind> notes) {
        BigDecimal rounded = p.unitPrice().setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(p.unitPrice()) != 0) {
            notes.add(Kind.PRODUCT_PRICE_ROUNDED);
        }
        return rounded.intValueExact(); // range checked by the validator (PRODUCT_INVALID)
    }

    /** Whether a Bref price fits a Keevo price (0 … 2 147 483 647 once rounded). */
    public static boolean priceInRange(BigDecimal unitPrice) {
        if (unitPrice == null) {
            return true;
        }
        BigDecimal rounded = unitPrice.setScale(0, RoundingMode.HALF_UP);
        return unitPrice.signum() >= 0 && rounded.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
    }

    /** The member whose price the group takes: the latest typed by a person, else the latest sold. */
    private static BrefProduct priceSource(List<BrefProduct> group) {
        Comparator<BrefProduct> recent = Comparator
                .comparing((BrefProduct p) -> p.lastSoldAt() == null ? p.updatedAt() : p.lastSoldAt())
                .thenComparing(BrefProduct::updatedAt)
                .thenComparing(BrefProduct::id);
        return group.stream().filter(BrefProduct::priceManual).max(Comparator.comparing(BrefProduct::updatedAt)
                        .thenComparing(BrefProduct::id))
                .orElseGet(() -> group.stream().max(recent).orElseThrow());
    }

    /** The final live product of a merge chain → its group's Keevo product; {@code null} when broken. */
    private static UUID resolve(BrefProduct p, Map<UUID, BrefProduct> byId, Map<UUID, UUID> liveTarget) {
        Set<UUID> visited = new HashSet<>();
        visited.add(p.id());
        BrefProduct current = p;
        while (true) {
            UUID next = BrefMappings.uuidOrNull(current.mergedIntoId());
            BrefProduct target = next == null ? null : byId.get(next);
            if (target == null || !visited.add(target.id())) {
                return null; // missing, foreign (not in this owner's catalogue) or cyclic
            }
            if (BrefMappings.trimmed(target.mergedIntoId()) == null) {
                return liveTarget.get(target.id());
            }
            current = target;
        }
    }

    /** A placeholder name for a row already imported (not written again; its stored name stays). */
    private static String stored(String raw) {
        String base = BrefMappings.trimmed(raw);
        return base == null ? FALLBACK_NAME : BrefMappings.cut(base, MAX_NAME).strip();
    }

    /** The product key as stored (cut to the column); {@code null} when blank. */
    private static String key(BrefProduct p) {
        String key = BrefMappings.trimmed(p.productKey());
        return key == null ? null : BrefMappings.cut(key, MAX_KEY);
    }

    /** Group of a live product: its key, or the product alone when it has none. */
    private static String groupKey(BrefProduct p) {
        String key = key(p);
        return key == null ? "id:" + p.id() : "key:" + key;
    }

    /** Names already given, case-insensitive (the {@code uq_products_name} rule). */
    private static final class Names {
        private final Set<String> taken;
        /** Next suffix to try per lower-cased base name: k clashes on one name cost O(k), not O(k²). */
        private final Map<String, Integer> next = new HashMap<>();

        Names(Set<String> alreadyTaken) {
            this.taken = new HashSet<>(alreadyTaken);
        }

        String claim(String raw, List<Kind> notes) {
            String base = BrefMappings.trimmed(raw);
            if (base == null) {
                base = FALLBACK_NAME;
                notes.add(Kind.PRODUCT_NAME_MISSING);
            } else if (base.codePointCount(0, base.length()) > MAX_NAME) {
                base = BrefMappings.cut(base, MAX_NAME).strip();
                notes.add(Kind.PRODUCT_NAME_SHORTENED);
            }
            if (taken.add(lower(base))) {
                return base;
            }
            String key = lower(base);
            for (int n = next.getOrDefault(key, 2); ; n++) {
                String suffix = " (" + n + ")";
                String candidate = BrefMappings.cut(base, MAX_NAME - suffix.length()).strip() + suffix;
                if (taken.add(lower(candidate))) {
                    next.put(key, n + 1);
                    notes.add(Kind.PRODUCT_RENAMED);
                    return candidate;
                }
            }
        }

        private static String lower(String name) {
            return Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
        }
    }
}
