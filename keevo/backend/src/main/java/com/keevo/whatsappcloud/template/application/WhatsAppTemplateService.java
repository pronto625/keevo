package com.keevo.whatsappcloud.template.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService;
import com.keevo.whatsappcloud.account.application.WhatsAppAccountService.Credentials;
import com.keevo.whatsappcloud.config.MetaErrors;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.SystemTemplates;
import com.keevo.whatsappcloud.template.domain.model.TemplateReview;
import com.keevo.whatsappcloud.template.domain.model.TemplateText;
import com.keevo.whatsappcloud.template.domain.port.out.MessageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.5 — the company's message templates and their Meta review (port of Bref {@code OwnerTemplateService}):
 * created DRAFT, submitted to Meta at once when the number is connected (else at connection), Meta's refusal kept on
 * the DRAFT ({@code submitError}) to correct or resubmit. Review results arrive by the webhook
 * ({@link #applyReviews}) and by a refresh when the list is read while something waits for review — at most one Graph
 * call per company per minute (Caffeine, bounded). The invoice template {@code bref_facture} is created on the account
 * at connection ({@link #onConnected}) and never listed.
 *
 * <p>Costs: list = 1 bounded scan (≤ 100 rows) [+ 1 Graph list + 1 set-based update, throttled]; create = 1 count +
 * 1 insert [+ 1 Graph create + 1 update]; connection = 1 sample upload + 1 create + 1 create per draft (≤ 100).
 */
@Service
public class WhatsAppTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppTemplateService.class);

    public static final int MAX_TEMPLATES = 100;
    static final Duration REFRESH_EVERY = Duration.ofMinutes(1);
    static final Set<String> CATEGORIES = Set.of("MARKETING", "UTILITY");
    static final Set<String> HEADERS = Set.of("NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT");
    static final Set<String> MEDIA_HEADERS = Set.of("IMAGE", "VIDEO", "DOCUMENT");

    /** A template the owner asks for (Bref's {@code CreateOwnerTemplateDto}, media header by upload handle). */
    public record NewTemplate(String name, String category, String language, String headerFormat, String headerText,
                              String headerHandle, String bodyText, List<String> bodyExamples, String footerText) {
    }

    private final MessageTemplateRepository templates;
    private final WhatsAppAccountService accounts;
    private final MetaGraphClient graph;
    private final Clock clock;
    private final Cache<String, Instant> lastRefresh = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterWrite(REFRESH_EVERY).build();
    /** Submissions after a connection: 1–2 threads, 100 queued connections; a full queue runs it on the caller. */
    private final java.util.concurrent.ThreadPoolExecutor connectionWork = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "whatsapp-templates-submit");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    {
        connectionWork.allowCoreThreadTimeOut(true);
    }

    public WhatsAppTemplateService(MessageTemplateRepository templates, WhatsAppAccountService accounts,
                                   MetaGraphClient graph, Clock clock) {
        this.templates = templates;
        this.accounts = accounts;
        this.graph = graph;
        this.clock = clock;
    }

    /** The owner's templates, newest first (system templates excluded); refreshed from Meta when one waits. */
    public List<MessageTemplate> list(String schema) {
        List<MessageTemplate> rows = templates.list(schema);
        boolean waiting = rows.stream().anyMatch(t -> MessageTemplate.PENDING.equals(t.status()));
        if (waiting && lastRefresh.asMap().putIfAbsent(schema, clock.instant()) == null && refresh(schema)) {
            rows = templates.list(schema);
        }
        return rows.stream().filter(t -> !t.system()).toList();
    }

    /** One template of the owner (system ones are not the owner's). */
    public MessageTemplate get(String schema, UUID id) {
        return templates.find(schema, id).filter(t -> !t.system())
                .orElseThrow(() -> new DomainException(ErrorCode.WHATSAPP_TEMPLATE_NOT_FOUND));
    }

    /**
     * Creates a template: DRAFT, then submitted to Meta when the number is connected.
     *
     * @throws InvalidRequestParameterException 400 (French) on an invalid field
     * @throws DomainException {@code WHATSAPP_TEMPLATE_LIMIT} / {@code WHATSAPP_TEMPLATE_NAME_TAKEN} 409
     */
    public MessageTemplate create(String schema, UUID actorId, NewTemplate request) {
        MessageTemplate draft = validated(request, actorId);
        if (templates.countOwned(schema) >= MAX_TEMPLATES) {
            throw new DomainException(ErrorCode.WHATSAPP_TEMPLATE_LIMIT);
        }
        if (!templates.insert(schema, draft)) {
            throw new DomainException(ErrorCode.WHATSAPP_TEMPLATE_NAME_TAKEN);
        }
        Optional<Credentials> credentials = accounts.credentials(schema);
        return credentials.map(c -> submit(schema, c, draft)).orElse(draft);
    }

    /**
     * Deletes a template; one already at Meta is deleted there first (Meta refusal → 502, nothing deleted). Without a
     * connected number, only Keevo's copy is deleted.
     */
    public void delete(String schema, UUID id) {
        MessageTemplate t = get(schema, id);
        if (t.metaTemplateId() != null) {
            Optional<Credentials> c = accounts.credentials(schema);
            if (c.isPresent()) {
                try {
                    graph.deleteTemplate(c.get().wabaId(), c.get().token(), t.name());
                } catch (MetaGraphException e) {
                    if (!goneAtMeta(c.get(), t.name())) {
                        throw MetaErrors.of(e, false);
                    }
                }
                // Meta deletes the name in every language: so does Keevo (owner's rows only)
                templates.deleteOwnedByName(schema, t.name());
                return;
            }
        }
        templates.delete(schema, id);
    }

    /**
     * Uploads the example media of a header (image, video, PDF) to Meta → the handle to put in {@code headerHandle}.
     *
     * @throws DomainException {@code WHATSAPP_NOT_CONNECTED} 409, {@code WHATSAPP_CLOUD_META_ERROR} 502
     */
    public String uploadHeaderSample(String schema, byte[] content, String mimeType, String filename) {
        Credentials c = accounts.credentials(schema)
                .orElseThrow(() -> new DomainException(ErrorCode.WHATSAPP_NOT_CONNECTED));
        try {
            return graph.uploadHeaderSample(c.token(), content, mimeType, filename);
        } catch (MetaGraphException e) {
            throw MetaErrors.of(e, false);
        }
    }

    /**
     * At the connection of the number: creates the invoice template on the account, then submits the drafts. Best
     * effort: a Meta refusal is kept on the template, never fails the connection.
     */
    public void onConnected(String schema) {
        Optional<Credentials> credentials = accounts.credentials(schema);
        if (credentials.isEmpty()) {
            return;
        }
        Credentials c = credentials.get();
        provisionInvoiceTemplate(schema, c);
        for (MessageTemplate t : templates.list(schema)) {
            if (!t.system() && MessageTemplate.DRAFT.equals(t.status())) {
                submit(schema, c, t);
            }
        }
        lastRefresh.invalidate(schema);
        refresh(schema);
    }

    /** Meta's review results (webhook), set-based. */
    public int applyReviews(String schema, List<TemplateReview> reviews) {
        return templates.applyReviews(schema, reviews);
    }

    /** The approved template {@code id} of the owner, to send. */
    public MessageTemplate approved(String schema, UUID id) {
        MessageTemplate t = get(schema, id);
        if (!MessageTemplate.APPROVED.equals(t.status())) {
            throw new DomainException(ErrorCode.WHATSAPP_TEMPLATE_NOT_APPROVED);
        }
        return t;
    }

    /** The invoice template when Meta approved it (the condition of sending invoices from the company number). */
    public boolean invoiceTemplateApproved(String schema) {
        Optional<MessageTemplate> t = templates.findByName(schema, SystemTemplates.INVOICE_NAME,
                SystemTemplates.INVOICE_LANGUAGE);
        if (t.isPresent() && MessageTemplate.PENDING.equals(t.get().status())
                && lastRefresh.asMap().putIfAbsent(schema, clock.instant()) == null && refresh(schema)) {
            // review webhook missed (module off meanwhile, subscription lost): Meta read at most once a minute
            t = templates.findByName(schema, SystemTemplates.INVOICE_NAME, SystemTemplates.INVOICE_LANGUAGE);
        }
        return t.map(x -> MessageTemplate.APPROVED.equals(x.status())).orElse(false);
    }

    /**
     * At the connection of a number on another WABA than before: the templates of the old account do not exist on the
     * new one — every template goes back to DRAFT (then submitted again by {@link #onConnected}).
     */
    public void resetForNewAccount(String schema) {
        templates.resetToDraft(schema);
        lastRefresh.invalidate(schema);
    }

    /** {@link #onConnected} on a small bounded pool: the connection answers at once (≤ 100 drafts → 100 Meta calls). */
    public void onConnectedAsync(String schema) {
        connectionWork.execute(() -> {
            try {
                onConnected(schema);
            } catch (RuntimeException e) {
                log.warn("[WhatsApp Cloud] templates not submitted after the connection of tenant {} ({})", schema,
                        e.getClass().getSimpleName());
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        connectionWork.shutdown();
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    /** A refused delete of a template Meta no longer has (deleted in WhatsApp Manager) is a delete. */
    private boolean goneAtMeta(Credentials c, String name) {
        try {
            return graph.listTemplates(c.wabaId(), c.token()).stream().noneMatch(m -> name.equals(m.name()));
        } catch (MetaGraphException e) {
            return false;
        }
    }

    private void provisionInvoiceTemplate(String schema, Credentials c) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MessageTemplate existing = templates.findByName(schema, SystemTemplates.INVOICE_NAME,
                SystemTemplates.INVOICE_LANGUAGE).orElse(null);
        if (existing != null && !MessageTemplate.DRAFT.equals(existing.status())) {
            return; // already at Meta (a refresh follows)
        }
        MessageTemplate row = existing;
        if (row == null) {
            row = new MessageTemplate(UUID.randomUUID(), SystemTemplates.INVOICE_NAME,
                    SystemTemplates.INVOICE_LANGUAGE, SystemTemplates.INVOICE_CATEGORY, "DOCUMENT", null, null,
                    SystemTemplates.INVOICE_BODY, SystemTemplates.INVOICE_EXAMPLES, SystemTemplates.INVOICE_FOOTER,
                    MessageTemplate.DRAFT, null, null, null, true, null, now, now);
            if (!templates.insert(schema, row)) {
                return; // created concurrently
            }
        }
        try {
            byte[] sample = SystemTemplates.samplePdf();
            String handle = graph.uploadHeaderSample(c.token(), sample, "application/pdf", "facture-exemple.pdf");
            MessageTemplate withHandle = new MessageTemplate(row.id(), row.name(), row.language(), row.category(),
                    row.headerFormat(), null, handle, row.bodyText(), row.bodyExamples(), row.footerText(),
                    row.status(), null, null, null, true, null, row.createdAt(), now);
            submit(schema, c, withHandle);
        } catch (MetaGraphException e) {
            // Bref: already created (account reconnected) or refused — the refresh adopts Meta's copy when it exists
            templates.updateSubmission(schema, row.submitFailed(MetaErrors.note(e), now));
            log.warn("[WhatsApp Cloud] invoice template not created for tenant {} ({})", schema, e.kind());
        }
    }

    /** Submits one template to Meta; a refusal keeps it DRAFT with Meta's note. */
    private MessageTemplate submit(String schema, Credentials c, MessageTemplate t) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MessageTemplate result;
        try {
            MetaTemplate created = graph.createTemplate(c.wabaId(), c.token(), new TemplateDraft(t.name(),
                    t.category(), t.language(), t.headerFormat(), t.headerText(), t.headerHandle(), t.bodyText(),
                    t.bodyExamples(), t.footerText()));
            result = t.submitted(created.id(), created.status(), now);
        } catch (MetaGraphException e) {
            result = t.submitFailed(MetaErrors.note(e), now);
            log.warn("[WhatsApp Cloud] template not submitted for tenant {} ({})", schema, e.kind());
        }
        templates.updateSubmission(schema, result);
        return result;
    }

    /** Meta's statuses of the company's templates applied in one statement; false when Meta was not read. */
    boolean refresh(String schema) {
        Optional<Credentials> c = accounts.credentials(schema);
        if (c.isEmpty()) {
            return false;
        }
        List<MetaTemplate> remote;
        try {
            remote = graph.listTemplates(c.get().wabaId(), c.get().token());
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] template statuses not refreshed for tenant {} ({})", schema, e.kind());
            return false;
        }
        // Meta's list (≤ 200) as is: applyReviews only matches the company's rows; one review per name + language
        Map<String, TemplateReview> reviews = new LinkedHashMap<>();
        for (MetaTemplate m : remote) {
            if (m.name() != null && m.language() != null) {
                reviews.put(m.name() + "|" + m.language(),
                        review(m.id(), m.name(), m.language(), m.status(), m.rejectedReason()));
            }
        }
        templates.applyReviews(schema, new ArrayList<>(reviews.values()));
        return true;
    }

    /** A review with Keevo's status and a bounded reason. */
    public static TemplateReview review(String metaId, String name, String language, String status, String reason) {
        String r = reason == null || reason.isBlank() || "NONE".equalsIgnoreCase(reason) ? null : reason.strip();
        if (r != null && r.length() > 200) {
            r = r.substring(0, 200);
        }
        return new TemplateReview(metaId, name, language, MessageTemplate.normalizeStatus(status), r);
    }

    private MessageTemplate validated(NewTemplate r, UUID actorId) {
        if (r == null) {
            throw invalid("body", "Modèle de message manquant");
        }
        String name = r.name() == null ? "" : r.name().strip();
        if (!name.matches("^[a-z0-9_]{1,512}$")) {
            throw invalid("name", "Le nom du modèle ne peut contenir que des minuscules, des chiffres et _");
        }
        if (SystemTemplates.INVOICE_NAME.equals(name)) {
            throw invalid("name", "Ce nom est réservé à l'envoi des factures");
        }
        String category = r.category() == null ? "" : r.category().strip().toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw invalid("category", "La catégorie doit être MARKETING ou UTILITY");
        }
        String language = r.language() == null ? "" : r.language().strip();
        if (!language.matches("^[a-z]{2,3}(_[A-Z]{2})?$")) {
            throw invalid("language", "Langue du modèle invalide (par exemple fr ou en_US)");
        }
        String headerText = blankToNull(r.headerText());
        String header = r.headerFormat() == null || r.headerFormat().isBlank()
                ? (headerText != null ? "TEXT" : "NONE") : r.headerFormat().strip().toUpperCase(java.util.Locale.ROOT);
        if (!HEADERS.contains(header)) {
            throw invalid("headerFormat", "Type d'en-tête invalide");
        }
        String handle = blankToNull(r.headerHandle());
        if ("TEXT".equals(header) && (headerText == null || headerText.length() > 60)) {
            throw invalid("headerText", "L'en-tête texte est obligatoire (60 caractères au plus)");
        }
        if ("TEXT".equals(header) && !TemplateText.variables(headerText).isEmpty()) {
            throw invalid("headerText", "L'en-tête texte ne peut pas contenir de variable");
        }
        if (MEDIA_HEADERS.contains(header) && (handle == null || handle.length() > 1024)) {
            throw invalid("headerHandle", "Ajoutez d'abord le média d'exemple de l'en-tête");
        }
        String body = r.bodyText() == null ? "" : r.bodyText().strip();
        if (body.isEmpty() || body.length() > 1024) {
            throw invalid("bodyText", "Le texte du message est obligatoire (1024 caractères au plus)");
        }
        List<String> variables = TemplateText.variables(body);
        for (int i = 0; i < variables.size(); i++) {
            if (!variables.get(i).equals(String.valueOf(i + 1))) {
                throw invalid("bodyText", "Utilisez des variables numérotées à la suite : {{1}}, {{2}}, …");
            }
        }
        List<String> examples = (r.bodyExamples() == null ? List.<String>of() : r.bodyExamples()).stream()
                .map(e -> e == null ? "" : e.strip()).filter(e -> !e.isEmpty()).toList();
        if (examples.size() < variables.size() || examples.stream().anyMatch(e -> e.length() > 200)) {
            throw invalid("bodyExamples", "Donnez un exemple (200 caractères au plus) pour chaque variable du message");
        }
        String footer = blankToNull(r.footerText());
        if (footer != null && footer.length() > 60) {
            throw invalid("footerText", "Le pied de message ne doit pas dépasser 60 caractères");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new MessageTemplate(UUID.randomUUID(), name, language, category, header,
                "TEXT".equals(header) ? headerText : null, MEDIA_HEADERS.contains(header) ? handle : null, body,
                examples.subList(0, variables.size()), footer, MessageTemplate.DRAFT, null, null, null, false,
                actorId, now, now);
    }

    private static InvalidRequestParameterException invalid(String field, String message) {
        return new InvalidRequestParameterException(field, "invalid", message);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
