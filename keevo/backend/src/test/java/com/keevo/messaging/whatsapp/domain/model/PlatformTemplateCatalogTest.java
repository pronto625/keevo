package com.keevo.messaging.whatsapp.domain.model;

import com.keevo.messaging.whatsapp.domain.model.PlatformTemplate.Category;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9c — the platform template catalogue respects Meta's template rules and covers every message kind. */
@DisplayName("PlatformTemplateCatalog — Meta rules and kind coverage (Story 19.9c)")
class PlatformTemplateCatalogTest {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9_]{1,512}$");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\d+)}}");
    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-");

    @Test
    @DisplayName("every kind has a template whose variable count is the kind's parameter count")
    void everyKindMapped() {
        for (PlatformMessageKind kind : PlatformMessageKind.values()) {
            PlatformTemplate template = PlatformTemplateCatalog.templateFor(kind);
            assertThat(template).as("template of %s", kind).isNotNull();
            assertThat(template.variableCount()).as("variables of %s", template.name())
                    .isEqualTo(kind.parameterCount());
        }
        assertThat(PlatformTemplateCatalog.mapping()).hasSize(PlatformMessageKind.values().length);
    }

    @Test
    @DisplayName("Meta rules: name, language fr, body ≤ 1024, variables 1..n, not at start/end, never adjacent")
    void metaRules() {
        Set<String> names = new HashSet<>();
        for (PlatformTemplate t : PlatformTemplateCatalog.all()) {
            assertThat(names.add(t.name())).as("duplicate %s", t.name()).isTrue();
            assertThat(t.name()).matches(NAME);
            assertThat(t.language()).isEqualTo("fr");
            assertThat(t.body()).hasSizeLessThanOrEqualTo(1024);
            assertThat(t.body().strip()).as("%s starts with a variable", t.name()).doesNotStartWith("{{");
            assertThat(t.body().strip()).as("%s ends with a variable", t.name()).doesNotEndWith("}}");
            assertThat(t.body()).as("%s adjacent variables", t.name()).doesNotContain("}}{{");
            Matcher m = VARIABLE.matcher(t.body());
            Set<Integer> seen = new HashSet<>();
            while (m.find()) seen.add(Integer.parseInt(m.group(1)));
            for (int i = 1; i <= t.variableCount(); i++) {
                assertThat(seen).as("%s numbering", t.name()).contains(i);
            }
            assertThat(t.examples()).as("%s examples", t.name()).hasSize(t.variableCount());
            if (t.footer() != null) assertThat(t.footer()).hasSizeLessThanOrEqualTo(60);
            assertThat(t.render(t.examples())).as("%s rendered example", t.name())
                    .hasSizeLessThanOrEqualTo(TemplateParameters.MAX_BODY).doesNotContain("{{");
            assertThat(t.body() + t.examples()).as("%s technical data", t.name()).doesNotContainPattern(UUID_LIKE);
        }
    }

    @Test
    @DisplayName("platform messages are UTILITY; only Bref's existing sign-in code template is AUTHENTICATION; no MARKETING")
    void categories() {
        assertThat(PlatformTemplateCatalog.mapping().values()).allMatch(t -> t.category() == Category.UTILITY);
        assertThat(PlatformTemplateCatalog.OTP.category()).isEqualTo(Category.AUTHENTICATION);
        assertThat(PlatformTemplateCatalog.all()).noneMatch(t -> t.category() == Category.MARKETING);
        assertThat(PlatformTemplateCatalog.all().get(0)).isEqualTo(PlatformTemplateCatalog.OTP);
    }

    @Test
    @DisplayName("a « detail » template leaves at least 800 characters to its variable")
    void detailBudget() {
        for (PlatformMessageKind kind : PlatformMessageKind.values()) {
            PlatformTemplate t = PlatformTemplateCatalog.templateFor(kind);
            if (t.variableCount() == 1) {
                assertThat(TemplateParameters.MAX_BODY - t.fixedLength()).as(t.name()).isGreaterThanOrEqualTo(800);
            }
        }
    }

    @Test
    @DisplayName("fixed templates keep the exact free-text wording sent today")
    void fixedWordings() {
        assertThat(PlatformTemplateCatalog.SUSPENDED.body())
                .isEqualTo("Votre compte Keevo est suspendu. Contactez le support Keevo.");
        assertThat(PlatformTemplateCatalog.DELETED.body())
                .isEqualTo("Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo.");
        assertThat(PlatformTemplateCatalog.REACTIVATED.body()).isEqualTo("Votre compte Keevo est réactivé.");
    }

    @Test
    @DisplayName("lookup by name, OTP included; unknown → empty")
    void byName() {
        assertThat(PlatformTemplateCatalog.byName("bref_point_otp")).contains(PlatformTemplateCatalog.OTP);
        assertThat(PlatformTemplateCatalog.byName("keevo_activite_equipe")).contains(PlatformTemplateCatalog.TEAM);
        assertThat(PlatformTemplateCatalog.byName("nope")).isEmpty();
        assertThat(PlatformTemplateCatalog.byName(null)).isEmpty();
        assertThat(PlatformTemplateCatalog.kindTemplateByName("bref_point_otp")).isEmpty();
        assertThat(PlatformTemplateCatalog.kindTemplateByName("keevo_alerte_stock")).isPresent();
    }

    @Test
    @DisplayName("UTILITY examples carry no promotional wording (Meta would move the template to MARKETING)")
    void noPromotionalExamples() {
        java.util.regex.Pattern promo = java.util.regex.Pattern.compile(
                "(?i)profitez|promo|offre spéciale|réduction|gratuit|!");
        for (PlatformTemplate t : PlatformTemplateCatalog.mapping().values()) {
            assertThat(t.body() + " " + String.join(" ", t.examples())).as(t.name()).doesNotContainPattern(promo);
        }
    }

    @Test
    @DisplayName("render replaces variables in order; {{0}} is left as is")
    void render() {
        assertThat(PlatformTemplateCatalog.MEMBER.render(List.of("Boutique Akwa", "Gérant")))
                .isEqualTo("Vous avez rejoint Boutique Akwa en tant que Gérant. Connectez-vous avec votre compte "
                        + "habituel, puis choisissez cette entreprise.");
        assertThat(new PlatformTemplate("t", Category.UTILITY, "fr", "a {{0}} b", null, List.of()).render(List.of("x")))
                .isEqualTo("a {{0}} b");
    }
}
