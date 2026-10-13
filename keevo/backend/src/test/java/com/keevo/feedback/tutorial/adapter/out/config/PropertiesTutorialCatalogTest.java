package com.keevo.feedback.tutorial.adapter.out.config;

import com.keevo.feedback.tutorial.domain.model.Tutorial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PropertiesTutorialCatalog (v1s-14-6)")
class PropertiesTutorialCatalogTest {

    private static TutorialProperties.Entry entry(String id, String title, String url) {
        var e = new TutorialProperties.Entry();
        e.setId(id);
        e.setModule("Vendre");
        e.setTitle(title);
        e.setUrl(url);
        return e;
    }

    @Test
    @DisplayName("maps entries; skips those without id or title; null url → blank")
    void mapsEntries() {
        var props = new TutorialProperties();
        props.setTutorials(List.of(entry("vendre", "Vendre", null), entry("", "x", "u"), entry("y", " ", "u")));

        List<Tutorial> all = new PropertiesTutorialCatalog(props).findAll();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).id()).isEqualTo("vendre");
        assertThat(all.get(0).url()).isEmpty();
        assertThat(all.get(0).durationSeconds()).isEqualTo(120);
    }
}
