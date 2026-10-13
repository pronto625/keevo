package com.keevo.feedback.tutorial.adapter.out.config;

import com.keevo.feedback.tutorial.domain.model.Tutorial;
import com.keevo.feedback.tutorial.domain.port.out.TutorialCatalogPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PropertiesTutorialCatalog — {@link TutorialCatalogPort} backed by {@code keevo.help.tutorials}
 * (v1s-14-6). Entries without id or title are skipped.
 */
@Component
@EnableConfigurationProperties(TutorialProperties.class)
public class PropertiesTutorialCatalog implements TutorialCatalogPort {

    private final TutorialProperties properties;

    public PropertiesTutorialCatalog(TutorialProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Tutorial> findAll() {
        return properties.getTutorials().stream()
                .filter(e -> e.getId() != null && !e.getId().isBlank()
                        && e.getTitle() != null && !e.getTitle().isBlank())
                .map(e -> new Tutorial(e.getId(), e.getModule() == null ? "" : e.getModule(),
                        e.getTitle(), e.getDescription(), e.getDurationSeconds(), e.getUrl()))
                .toList();
    }
}
