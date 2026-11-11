package com.keevo.catalog.product.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductSkus — automatic SKUs unique among themselves and against the catalogue")
class ProductSkusTest {

    @Test
    @DisplayName("taken candidates are replaced, all distinct, count kept, one uniqueness check")
    void takenReplaced() {
        AtomicInteger calls = new AtomicInteger();
        List<String> firstAsked = new ArrayList<>();
        List<String> skus = ProductSkus.unique(50, candidates -> {
            calls.incrementAndGet();
            firstAsked.addAll(candidates);
            return candidates.stream().limit(10).toList(); // the first 10 are « already used »
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(skus).hasSize(50).doesNotHaveDuplicates().allMatch(s -> s.matches("KEV-[A-Z0-9]{6}"));
        assertThat(new HashSet<>(skus)).doesNotContainAnyElementsOf(firstAsked.subList(0, 10));
    }

    @Test
    @DisplayName("zero SKUs: no query")
    void none() {
        assertThat(ProductSkus.unique(0, c -> {
            throw new AssertionError("no query expected");
        })).isEmpty();
    }
}
