package com.keevo.feedback.tutorial.domain.model;

/**
 * Tutorial — one short French video tutorial of the « Aide & Tutoriels » page (FR7, v1s-14-6).
 *
 * <p>Pure Java. {@code url} is an external http(s) link (e.g. YouTube) or blank when the video is
 * not published yet (« Bientôt disponible »). The app never hosts nor plays the video itself.
 */
public record Tutorial(
        String id,
        String module,
        String title,
        String description,
        int durationSeconds,
        String url
) {
    public Tutorial {
        url = url == null ? "" : url;
        description = description == null ? "" : description;
    }

    public Tutorial withUrl(String newUrl) {
        return new Tutorial(id, module, title, description, durationSeconds, newUrl);
    }
}
