package com.keevo.feedback.tutorial.application.service;

import com.keevo.feedback.tutorial.domain.model.Tutorial;
import com.keevo.feedback.tutorial.domain.port.in.ListTutorialsUseCase;
import com.keevo.feedback.tutorial.domain.port.out.TutorialCatalogPort;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * ListTutorialsService — FR7 (v1s-14-6). Returns the configured catalog with every URL that is
 * not an absolute http(s) link (with a host) replaced by a blank string, so the app shows the
 * entry as « Bientôt disponible » instead of opening something unsafe.
 */
@Service
public class ListTutorialsService implements ListTutorialsUseCase {

    private final TutorialCatalogPort catalog;

    public ListTutorialsService(TutorialCatalogPort catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<Tutorial> list() {
        return catalog.findAll().stream()
                .map(t -> t.withUrl(sanitize(t.url())))
                .toList();
    }

    static String sanitize(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return trimmed;
            }
        } catch (Exception ignored) {
            // malformed → blank
        }
        return "";
    }
}
