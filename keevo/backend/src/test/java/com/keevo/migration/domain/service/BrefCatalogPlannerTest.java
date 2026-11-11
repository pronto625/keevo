package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.service.BrefCatalogPlanner.CatalogPlan;
import com.keevo.migration.domain.service.BrefCatalogPlanner.PlannedProduct;
import com.keevo.migration.domain.service.BrefCatalogPlanner.SourceLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.3 — Bref catalogue plan (pure): key groups, merges, names, prices")
class BrefCatalogPlannerTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private static final String SHOP_A = UUID.randomUUID().toString();
    private static final String SHOP_B = UUID.randomUUID().toString();

    private static BrefProduct p(String shop, String name, String key, String ref, String price, boolean manual,
                                 Instant lastSold, boolean archived, String mergedInto, int createdSec) {
        return new BrefProduct(UUID.randomUUID(), shop, name, name, key, ref, new BigDecimal(price), manual, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, lastSold, archived, mergedInto, T.plusSeconds(createdSec),
                T.plusSeconds(createdSec + 1));
    }

    private static Map<UUID, PlannedProduct> byId(CatalogPlan plan) {
        return plan.products().stream().collect(Collectors.toMap(PlannedProduct::id, Function.identity()));
    }

    private static SourceLink link(CatalogPlan plan, BrefProduct source) {
        return plan.links().stream().filter(l -> l.source().id().equals(source.id())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("same key in two shops: one product (oldest id, name, date), latest sale price, records for both")
    void keyGroup() {
        BrefProduct a = p(SHOP_A, "Riz parfumé", "nm:riz parfume", null, "1500", false, T.plusSeconds(10), false, null,
                1);
        BrefProduct b = p(SHOP_B, "riz parfume", "nm:riz parfume", "12345678", "1600", false, T.plusSeconds(20), true,
                null, 2);
        CatalogPlan plan = BrefCatalogPlanner.plan(List.of(b, a));
        assertThat(plan.products()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(a.id());
            assertThat(r.name()).isEqualTo("Riz parfumé");
            assertThat(r.price()).isEqualTo(1600);
            assertThat(r.barcode()).isEqualTo("12345678");
            assertThat(r.archived()).isFalse(); // one member still live
            assertThat(r.createdAt()).isEqualTo(a.createdAt());
            assertThat(r.updatedAt()).isEqualTo(b.updatedAt());
            assertThat(r.productKey()).isEqualTo("nm:riz parfume");
        });
        assertThat(link(plan, b).targetId()).isEqualTo(a.id());
        assertThat(link(plan, b).notes()).containsExactly(Kind.PRODUCT_MERGED_BY_KEY);
        assertThat(link(plan, a).notes()).isEmpty();
    }

    @Test
    @DisplayName("a price typed by a person wins over a more recent sale; all members archived → archived")
    void manualPriceAndArchived() {
        BrefProduct a = p(SHOP_A, "Savon", "nm:savon", null, "250", true, null, true, null, 1);
        BrefProduct b = p(SHOP_B, "Savon", "nm:savon", null, "300", false, T.plusSeconds(99), true, null, 2);
        PlannedProduct r = BrefCatalogPlanner.plan(List.of(a, b)).products().get(0);
        assertThat(r.price()).isEqualTo(250);
        assertThat(r.priceManual()).isTrue();
        assertThat(r.archived()).isTrue();
    }

    @Test
    @DisplayName("merged in Bref: own archived row redirected to the final target's group; chains, broken and cycles")
    void merges() {
        BrefProduct target = p(SHOP_A, "Coca-Cola", "nm:coca-cola", null, "500", false, null, false, null, 1);
        BrefProduct targetB = p(SHOP_B, "Coca-Cola", "nm:coca-cola", null, "500", false, null, false, null, 2);
        BrefProduct mid = p(SHOP_B, "Coca 1L", "nm:coca 1l", null, "450", false, null, true,
                targetB.id().toString().toUpperCase(), 3);
        BrefProduct chained = p(SHOP_B, "Coca", "nm:coca", null, "400", false, null, true, mid.id().toString(), 4);
        BrefProduct broken = p(SHOP_A, "Fantôme", "nm:fantome", null, "1", false, null, true,
                UUID.randomUUID().toString(), 5);
        BrefProduct junk = p(SHOP_A, "Bizarre", "nm:bizarre", null, "1", false, null, true, "pas-un-uuid", 6);
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        BrefProduct cycleX = new BrefProduct(x, SHOP_A, "X", "x", "nm:x", null, BigDecimal.ONE, false, 0, null, null,
                null, true, y.toString(), T.plusSeconds(7), T.plusSeconds(7));
        BrefProduct cycleY = new BrefProduct(y, SHOP_A, "Y", "y", "nm:y", null, BigDecimal.ONE, false, 0, null, null,
                null, true, x.toString(), T.plusSeconds(8), T.plusSeconds(8));
        CatalogPlan plan = BrefCatalogPlanner.plan(List.of(target, targetB, mid, chained, broken, junk, cycleX,
                cycleY));
        Map<UUID, PlannedProduct> rows = byId(plan);
        assertThat(rows).containsOnlyKeys(target.id(), mid.id(), chained.id(), broken.id(), junk.id(), x, y);
        assertThat(rows.get(mid.id()).mergedIntoId()).isEqualTo(target.id()); // targetB's group = target
        assertThat(rows.get(chained.id()).mergedIntoId()).isEqualTo(target.id()); // one hop to the final group
        assertThat(rows.get(chained.id()).archived()).isTrue();
        assertThat(rows.get(broken.id()).mergedIntoId()).isNull();
        assertThat(rows.get(junk.id()).mergedIntoId()).isNull();
        assertThat(rows.get(x).mergedIntoId()).isNull();
        assertThat(link(plan, mid).notes()).containsExactly(Kind.PRODUCT_MERGED_IN_BREF);
        assertThat(link(plan, broken).notes()).containsExactly(Kind.PRODUCT_MERGE_BROKEN);
        assertThat(link(plan, cycleY).notes()).containsExactly(Kind.PRODUCT_MERGE_BROKEN);
        assertThat(link(plan, targetB).targetId()).isEqualTo(target.id());
        assertThat(plan.links()).hasSize(8);
    }

    @Test
    @DisplayName("names: unique case-insensitively (live groups first), « (n) » suffix, cut to 200, blank fallback")
    void names() {
        BrefProduct savonBc = p(SHOP_A, "Savon", "bc:6111234567890", "6111234567890", "1", false, null, false, null, 1);
        BrefProduct savonNm = p(SHOP_B, "SAVON", "nm:savon", null, "1", false, null, false, null, 2);
        BrefProduct merged = p(SHOP_A, "savon", "nm:savon-old", null, "1", false, null, true,
                savonBc.id().toString(), 0); // oldest, but merged rows are named after the live groups
        BrefProduct savon3 = p(SHOP_A, "Savon (2)", "nm:savon (2)", null, "1", false, null, false, null, 3);
        String longName = "N".repeat(205);
        BrefProduct lng = p(SHOP_A, longName, "nm:long", null, "1", false, null, false, null, 4);
        BrefProduct lng2 = p(SHOP_B, longName + "x", "nm:long2", null, "1", false, null, false, null, 5);
        BrefProduct blank = p(SHOP_A, "  ", "nm:", null, "1", false, null, false, null, 6);
        Map<UUID, PlannedProduct> rows = byId(BrefCatalogPlanner.plan(List.of(savonBc, savonNm, merged, savon3, lng,
                lng2, blank)));
        assertThat(rows.get(savonBc.id()).name()).isEqualTo("Savon");
        assertThat(rows.get(savonNm.id()).name()).isEqualTo("SAVON (2)");
        assertThat(rows.get(savon3.id()).name()).isEqualTo("Savon (2) (2)");
        assertThat(rows.get(merged.id()).name()).isEqualTo("savon (3)");
        assertThat(rows.get(lng.id()).name()).hasSize(200);
        assertThat(rows.get(lng2.id()).name()).hasSize(200).endsWith(" (2)");
        assertThat(rows.get(blank.id()).name()).isEqualTo(BrefCatalogPlanner.FALLBACK_NAME);
        List<String> lower = new ArrayList<>(rows.values().stream().map(r -> r.name().toLowerCase()).toList());
        assertThat(lower).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("prices: cents rounded half-up and noted; range check; a too long reference is not kept")
    void pricesAndReference() {
        BrefProduct cents = p(SHOP_A, "Bonbon", "nm:bonbon", "R".repeat(101), "250.50", false, null, false, null, 1);
        CatalogPlan plan = BrefCatalogPlanner.plan(List.of(cents));
        assertThat(plan.products().get(0).price()).isEqualTo(251);
        assertThat(plan.products().get(0).barcode()).isNull();
        assertThat(link(plan, cents).notes()).containsExactlyInAnyOrder(Kind.PRODUCT_PRICE_ROUNDED,
                Kind.PRODUCT_REFERENCE_DROPPED);
        assertThat(BrefCatalogPlanner.priceInRange(new BigDecimal("-0.01"))).isFalse(); // negative: blocked
        assertThat(BrefCatalogPlanner.priceInRange(new BigDecimal("-1"))).isFalse();
        assertThat(BrefCatalogPlanner.priceInRange(new BigDecimal("2147483647.49"))).isTrue();
        assertThat(BrefCatalogPlanner.priceInRange(new BigDecimal("2147483647.50"))).isFalse();
    }

    @Test
    @DisplayName("re-run: stored names are never given to a new product; already imported rows are not renamed")
    void stableNames() {
        BrefProduct stored = p(SHOP_A, "Savon", "nm:savon", null, "1", false, null, false, null, 1);
        BrefProduct storedTwo = p(SHOP_A, "Lait", "nm:lait", null, "1", false, null, false, null, 2);
        BrefProduct fresh = p(SHOP_B, "SAVON", "bc:12345678", "12345678", "1", false, null, false, null, 0);
        Map<UUID, PlannedProduct> rows = byId(BrefCatalogPlanner.plan(List.of(stored, storedTwo, fresh),
                java.util.Set.of(stored.id(), storedTwo.id()), java.util.Set.of("savon", "savon (2)", "lait")));
        assertThat(rows.get(fresh.id()).name()).isEqualTo("SAVON (3)");
        assertThat(rows.get(stored.id()).name()).isEqualTo("Savon");
    }

    @Test
    @DisplayName("deterministic: any input order gives the same plan")
    void deterministic() {
        List<BrefProduct> list = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            list.add(p(i % 2 == 0 ? SHOP_A : SHOP_B, "Produit " + (i % 7), "nm:produit " + (i % 5), null, "10", false,
                    null, false, null, i));
        }
        CatalogPlan first = BrefCatalogPlanner.plan(list);
        Collections.shuffle(list);
        CatalogPlan second = BrefCatalogPlanner.plan(list);
        assertThat(second.products()).containsExactlyElementsOf(first.products());
        assertThat(second.links()).containsExactlyElementsOf(first.links());
    }
}
