package com.keevo.whatsappcloud.template.domain.port.out;

import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.TemplateReview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Story 21.5 — the company's message templates (module table {@code whatsapp_templates} of the tenant schema). */
public interface MessageTemplateRepository {

    /** Every template (system ones included), newest first. */
    List<MessageTemplate> list(String schema);

    Optional<MessageTemplate> find(String schema, UUID id);

    Optional<MessageTemplate> findByName(String schema, String name, String language);

    /** The owner's templates (system ones excluded). */
    long countOwned(String schema);

    /** @return false when a template already has this name and language (nothing written) */
    boolean insert(String schema, MessageTemplate template);

    /** Replaces the submission fields (status, reason, error, Meta id) of a template, by id. */
    void updateSubmission(String schema, MessageTemplate template);

    /**
     * Applies Meta's review results in one set-based statement (by Meta's id, else name + language). A DRAFT the owner
     * never submitted is matched by Meta's id only — a same-name template of the WABA may have another body — except the
     * system templates, whose content is fixed by their name (adopted when the WABA already holds them).
     *
     * @return the rows changed
     */
    int applyReviews(String schema, List<TemplateReview> reviews);

    void delete(String schema, UUID id);

    /** Deletes the owner's templates of {@code name} in every language (Meta deletes them all). */
    void deleteOwnedByName(String schema, String name);

    /** Every template back to DRAFT, Meta's id, status reason and error cleared (number moved to another WABA). */
    void resetToDraft(String schema);
}
