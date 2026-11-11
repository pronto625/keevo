package com.keevo.messaging.whatsapp.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9c — Meta parameter rules: one line, no tab, no 5+ spaces, never empty, rendered body ≤ 1024. */
@DisplayName("TemplateParameters (Story 19.9c)")
class TemplateParametersTest {

    @Test
    @DisplayName("lines joined with « · », whitespace collapsed, blank → « - »")
    void flatten() {
        assertThat(TemplateParameters.flatten("Rapport\n\n  💰 CA : 5 000\r\n• Riz\t\t:     3  "))
                .isEqualTo("Rapport · 💰 CA : 5 000 · • Riz : 3");
        assertThat(TemplateParameters.flatten("  \n ")).isEqualTo("-");
        assertThat(TemplateParameters.flatten(null)).isEqualTo("-");
        assertThat(TemplateParameters.flatten("a  b")).isEqualTo("a b");
    }

    @Test
    @DisplayName("short values are kept as they are")
    void shortKept() {
        assertThat(TemplateParameters.fit(PlatformTemplateCatalog.INVITATION, List.of("Boutique Akwa", "Gérant")))
                .contains(List.of("Boutique Akwa", "Gérant"));
    }

    @Test
    @DisplayName("too long: the longest value is cut with …, rendered body exactly within the limit")
    void longestCut() {
        String detail = "x".repeat(3000);
        List<String> fitted = TemplateParameters.fit(PlatformTemplateCatalog.DAILY, List.of(detail)).orElseThrow();
        assertThat(fitted.get(0)).endsWith("…");
        assertThat(PlatformTemplateCatalog.DAILY.render(fitted)).hasSizeLessThanOrEqualTo(TemplateParameters.MAX_BODY);
        assertThat(PlatformTemplateCatalog.DAILY.render(fitted).length()).isGreaterThan(TemplateParameters.MAX_BODY - 5);
    }

    @Test
    @DisplayName("structured template too long: never cut (a cut link or reference is wrong) — empty, free text")
    void structuredNeverCut() {
        List<String> values = List.of("Aminata", "facture", "FAC-1", "y".repeat(1000), "28 850", "https://k.cm/l/abc");
        assertThat(TemplateParameters.fit(PlatformTemplateCatalog.DOCUMENT, values)).isEmpty();
        List<String> ok = List.of("Aminata", "facture", "FAC-1", "Boutique", "28 850", "https://k.cm/l/abc");
        assertThat(TemplateParameters.fit(PlatformTemplateCatalog.DOCUMENT, ok)).contains(ok);
    }

    @Test
    @DisplayName("an emoji is never split by the cut")
    void emojiNotSplit() {
        String detail = "😀".repeat(1500);
        String cut = TemplateParameters.fit(PlatformTemplateCatalog.DAILY, List.of(detail)).orElseThrow().get(0);
        String withoutEllipsis = cut.substring(0, cut.length() - 1);
        assertThat(withoutEllipsis.codePoints().allMatch(cp -> cp == "😀".codePointAt(0))).isTrue();
    }
}
