package com.keevo.whatsappcloud.template.domain.model;

import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Meta template components: header media, numbered / named variables (21.6)")
class TemplateComponentsTest {

    @Test
    @DisplayName("media header by id or link, then the body values in order")
    void mediaHeaderAndBody() {
        List<Map<String, Object>> c = TemplateComponents.build("Bonjour {{1}} de {{2}}", List.of("Awa", "Bref"),
                "IMAGE", new HeaderMedia("123", null));
        assertThat(c).hasSize(2);
        assertThat(c.get(0).toString()).contains("type=header").contains("image={id=123}");
        assertThat(c.get(1).toString()).contains("text=Awa").contains("text=Bref").doesNotContain("parameter_name");
        assertThat(TemplateComponents.build("x", List.of(), "DOCUMENT", new HeaderMedia(null, "https://a.cm/f.pdf"))
                .get(0).toString()).contains("document={link=https://a.cm/f.pdf}");
    }

    @Test
    @DisplayName("named variables carry their parameter_name; no variable and no media = no component")
    void namedVariables() {
        List<Map<String, Object>> c = TemplateComponents.build("Bonjour {{client}}, voir {{boutique}}",
                List.of("Awa", "Bref"), "NONE", null);
        assertThat(c).hasSize(1);
        assertThat(c.get(0).toString()).contains("parameter_name=client").contains("parameter_name=boutique");
        assertThat(TemplateComponents.build("Bonjour", List.of(), "TEXT", null)).isEmpty();
    }

    @Test
    @DisplayName("header media rules: required and alone for media templates, refused otherwise, https only")
    void headerProblems() {
        assertThat(TemplateComponents.headerProblem("NONE", null)).isNull();
        assertThat(TemplateComponents.headerProblem("NONE", new HeaderMedia("1", null))).isNotNull();
        assertThat(TemplateComponents.headerProblem("IMAGE", null)).contains("image");
        assertThat(TemplateComponents.headerProblem("VIDEO", new HeaderMedia("1", "https://a.cm/v.mp4"))).isNotNull();
        assertThat(TemplateComponents.headerProblem("IMAGE", new HeaderMedia(null, "http://a.cm/i.png")))
                .contains("https://");
        assertThat(TemplateComponents.headerProblem("IMAGE", new HeaderMedia("bad id!", null))).isNotNull();
        assertThat(TemplateComponents.headerProblem("DOCUMENT", new HeaderMedia(null, "https://a.cm/f.pdf"))).isNull();
    }
}
