package com.keevo.whatsappcloud.template.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TemplateText / SystemTemplates — variables, rendering, sample PDF (21.5)")
class TemplateTextTest {

    @Test
    @DisplayName("numbered variables in number order, named in order of appearance, rendering")
    void variables() {
        assertThat(TemplateText.variables("{{2}} et {{1}} puis {{2}}")).containsExactly("1", "2");
        assertThat(TemplateText.variables("Bonjour {{name}}, {{shop}}")).containsExactly("name", "shop");
        assertThat(TemplateText.render(SystemTemplates.INVOICE_BODY, List.of("Awa", "facture", "FAC-1", "Boutique $1")))
                .isEqualTo("Bonjour Awa, voici votre facture FAC-1 de Boutique $1. Merci pour votre confiance !");
        assertThat(TemplateText.render("{{1}} {{2}}", List.of("a"))).isEqualTo("a {{2}}");
    }

    @Test
    @DisplayName("the sample PDF is a one-page PDF")
    void samplePdf() {
        String pdf = new String(SystemTemplates.samplePdf(), StandardCharsets.ISO_8859_1);
        assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 1");
    }
}
