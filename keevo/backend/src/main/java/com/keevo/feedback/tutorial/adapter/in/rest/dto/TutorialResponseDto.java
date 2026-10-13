package com.keevo.feedback.tutorial.adapter.in.rest.dto;

import com.keevo.feedback.tutorial.domain.model.Tutorial;

/**
 * TutorialResponseDto — one FR7 tutorial entry (v1s-14-6). {@code url} blank = not published yet.
 */
public record TutorialResponseDto(
        String id,
        String module,
        String title,
        String description,
        int durationSeconds,
        String url
) {
    public static TutorialResponseDto fromDomain(Tutorial t) {
        return new TutorialResponseDto(t.id(), t.module(), t.title(), t.description(),
                t.durationSeconds(), t.url());
    }
}
