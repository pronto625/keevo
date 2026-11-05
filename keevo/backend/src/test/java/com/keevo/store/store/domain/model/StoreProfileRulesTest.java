package com.keevo.store.store.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreCategory;
import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StoreProfileRules — Bref fields of a store (Story 19.12)")
class StoreProfileRulesTest {

    private static final Instant T = Instant.parse("2026-11-05T10:00:00Z");

    private static Store filled() {
        return new Store(UUID.randomUUID(), "Boutique", StoreType.STORE, "Yaoundé", "+237690000001", true, T, T,
                StoreCategory.FOOD, "Épicerie fine", null, "M0123", "RC/YAO/2020/B/1", "CM");
    }

    private static StoreProfilePatch patch(String category, String description, String niu, String rccm,
                                           String country) {
        return new StoreProfilePatch(category, description, niu, rccm, country);
    }

    private static void assertInvalid(StoreProfilePatch p, String message) {
        assertThatThrownBy(() -> StoreProfileRules.normalize(p))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getDomainCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(d.getUserMessage()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("the 19 Bref keys, exactly, each with a French label")
    void nineteenKeys() {
        assertThat(Arrays.stream(StoreCategory.values()).map(StoreCategory::key)).containsExactly(
                "fashion", "electronics", "telecom", "food", "restaurant", "beauty", "sports", "home", "furniture",
                "hardware", "automotive", "agriculture", "stationery", "jewelry", "events", "education",
                "real_estate", "services", "other");
        for (StoreCategory c : StoreCategory.values()) {
            assertThat(StoreCategory.fromKey(c.key())).contains(c);
            assertThat(StoreProfileRules.normalize(patch(c.key(), null, null, null, null)).category())
                    .isEqualTo(c.key());
            assertThat(c.label()).isNotBlank().doesNotContain("_");
        }
        assertThat(StoreCategory.fromKey(null)).isEmpty();
        assertThat(StoreCategory.fromKey("FOOD")).isEmpty();
    }

    @Test
    @DisplayName("V1 patch (all absent): nothing changes")
    void absentKeepsEverything() {
        Store s = filled();
        assertThat(StoreProfileRules.apply(s, StoreProfilePatch.NONE)).isSameAs(s);
        assertThat(StoreProfileRules.apply(s, null)).isSameAs(s);
        assertThat(StoreProfileRules.normalize(patch(null, null, null, null, null))).isEqualTo(StoreProfilePatch.NONE);
    }

    @Test
    @DisplayName("set: category key, country upper-cased, values trimmed")
    void setFields() {
        Store s = StoreProfileRules.apply(filled(),
                patch("real_estate", "  Agence  ", " M0123 ", " RC 1 ", "ci"));
        assertThat(s.category()).isEqualTo(StoreCategory.REAL_ESTATE);
        assertThat(s.description()).isEqualTo("Agence");
        assertThat(s.niu()).isEqualTo("M0123");
        assertThat(s.rccm()).isEqualTo("RC 1");
        assertThat(s.country()).isEqualTo("CI");
        assertThat(StoreProfileRules.normalize(patch(null, null, null, null, " sn ")).country()).isEqualTo("SN");
    }

    @Test
    @DisplayName("empty / blank clears category, description, niu, rccm; the others are kept")
    void blankClears() {
        Store s = StoreProfileRules.apply(filled(), patch("", "   ", "", " ", null));
        assertThat(s.category()).isNull();
        assertThat(s.description()).isNull();
        assertThat(s.niu()).isNull();
        assertThat(s.rccm()).isNull();
        assertThat(s.country()).isEqualTo("CM");
        Store onlyDescription = StoreProfileRules.apply(filled(), patch(null, "", null, null, null));
        assertThat(onlyDescription.description()).isNull();
        assertThat(onlyDescription.category()).isEqualTo(StoreCategory.FOOD);
        assertThat(onlyDescription.niu()).isEqualTo("M0123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alimentation", "unknown", "real-estate", "realestate"})
    @DisplayName("unknown category → VALIDATION_ERROR « Catégorie de boutique inconnue »")
    void badCategory(String category) {
        assertInvalid(patch(category, null, null, null, null), "Catégorie de boutique inconnue");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CMR", "C", "1M", "C M", "é1", "ZZ", "zz", "XX"})
    @DisplayName("country not an ISO 3166-1 alpha-2 code → VALIDATION_ERROR")
    void badCountry(String country) {
        assertInvalid(patch(null, null, null, null, country),
                "Pays inconnu : indiquez le code du pays en deux lettres (par exemple CM)");
    }

    @Test
    @DisplayName("category key trimmed and lower-cased (« Food » → food, « REAL_ESTATE » → real_estate)")
    void categoryCaseInsensitive() {
        assertThat(StoreProfileRules.normalize(patch(" Food ", null, null, null, null)).category()).isEqualTo("food");
        assertThat(StoreProfileRules.normalize(patch("REAL_ESTATE", null, null, null, null)).category())
                .isEqualTo("real_estate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("country can never be cleared → VALIDATION_ERROR")
    void blankCountry(String country) {
        assertInvalid(patch(null, null, null, null, country), "Le pays de la boutique est obligatoire");
    }

    @Test
    @DisplayName("lengths: description ≤ 1000, niu ≤ 30, rccm ≤ 50 (trimmed); over → VALIDATION_ERROR")
    void lengths() {
        assertThat(StoreProfileRules.normalize(patch(null, "d".repeat(1000), " " + "n".repeat(30) + " ",
                "r".repeat(50), null)).niu()).hasSize(30);
        assertInvalid(patch(null, "d".repeat(1001), null, null, null),
                "La description ne doit pas dépasser 1000 caractères");
        assertInvalid(patch(null, null, "n".repeat(31), null, null), "Le NIU ne doit pas dépasser 30 caractères");
        assertInvalid(patch(null, null, null, "r".repeat(51), null), "Le RCCM ne doit pas dépasser 50 caractères");
    }

    @Test
    @DisplayName("creation: omitted fields null, country CM")
    void createDefaults() {
        Store s = StoreFactory.create("Boutique", null, null, null, StoreProfilePatch.NONE);
        assertThat(List.of(String.valueOf(s.category()), String.valueOf(s.description()), String.valueOf(s.niu()),
                String.valueOf(s.rccm()), String.valueOf(s.logoPath()))).containsOnly("null");
        assertThat(s.country()).isEqualTo("CM");
        Store withFields = StoreFactory.create("Boutique", null, null, null, patch("food", null, null, null, "ga"));
        assertThat(withFields.category()).isEqualTo(StoreCategory.FOOD);
        assertThat(withFields.country()).isEqualTo("GA");
    }

    @Test
    @DisplayName("changes: only the differing fields, the category as its French label")
    void changes() {
        Store before = filled();
        Store after = StoreProfileRules.apply(before, patch("real_estate", "", null, null, "CI"));
        StoreProfileRules.Changes c = StoreProfileRules.changes(before, after);
        assertThat(c.before()).isEqualTo(Map.of("category", "Alimentation", "description", "Épicerie fine",
                "country", "CM"));
        assertThat(c.after()).isEqualTo(Map.of("category", "Immobilier", "country", "CI"));
        assertThat(StoreProfileRules.changes(before, before).isEmpty()).isTrue();
    }
}
