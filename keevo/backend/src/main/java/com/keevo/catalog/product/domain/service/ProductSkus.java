package com.keevo.catalog.product.domain.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * ProductSkus — automatic SKUs ({@code KEV-} + 6 upper-case letters or digits, the {@code Product} format), shared by
 * the products derived from a document (20.6) and the products imported from Bref (24.3). Domain Service, no state.
 */
public final class ProductSkus {

    private static final String SKU_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private ProductSkus() {
    }

    /** One random SKU. */
    public static String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder("KEV-");
        for (int i = 0; i < 6; i++) {
            sb.append(SKU_CHARS.charAt(random.nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * {@code count} SKUs, unique among themselves and against the catalogue: {@code taken} answers, in one call, which
     * of the candidates are already used (one query); a taken one (~1 in 2 billion) is regenerated without a second
     * check.
     */
    public static List<String> unique(int count, Function<Collection<String>, Collection<String>> taken) {
        Set<String> skus = new HashSet<>();
        List<String> ordered = new ArrayList<>(count);
        while (ordered.size() < count) {
            String sku = generate();
            if (skus.add(sku)) {
                ordered.add(sku);
            }
        }
        if (count == 0) {
            return ordered;
        }
        Set<String> used = new HashSet<>(taken.apply(skus));
        for (int i = 0; i < ordered.size(); i++) {
            while (used.contains(ordered.get(i))) {
                String sku = generate();
                if (skus.add(sku)) {
                    ordered.set(i, sku);
                }
            }
        }
        return ordered;
    }
}
