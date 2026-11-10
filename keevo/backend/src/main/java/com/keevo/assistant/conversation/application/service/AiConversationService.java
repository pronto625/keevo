package com.keevo.assistant.conversation.application.service;

import com.keevo.assistant.chat.application.service.AssistantChatService;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import com.keevo.assistant.conversation.domain.model.AiConversation;
import com.keevo.assistant.conversation.domain.model.AiConversationSummary;
import com.keevo.assistant.conversation.domain.port.out.AiConversationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 21.4 — the assistant conversations of a user (Bref Point's {@code /v1/ai/conversations}): list, create, read,
 * save, delete, delete several. Each user sees only their own conversations, inside their company.
 *
 * <p>Bounds: the list returns the {@value #LIST_LIMIT} most recent (no message content); a user keeps at most
 * {@value #MAX_PER_USER} conversations ({@code AI_CONVERSATION_LIMIT} beyond). The title is the one given (offline
 * creation), else a model title (at most {@value #TITLES_PER_HOUR} per user and hour, never counted as a question),
 * else the first {@value #FALLBACK_TITLE_CHARS} characters of the first message — never an identifier.
 *
 * <p>Cost: list = 1 indexed query; create = 1 indexed count, then in one transaction a per-user advisory lock + 1
 * indexed count + 1 insert (+ at most 1 model call outside any
 * transaction); read, save, delete = 1 primary-key statement; delete several = 1 {@code id = ANY(?)} statement.
 */
@Service
public class AiConversationService {

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);

    public static final int LIST_LIMIT = 100;
    public static final int MAX_PER_USER = 200;
    public static final int MAX_TITLE_CHARS = 120;
    static final int FALLBACK_TITLE_CHARS = 60;
    static final int TITLES_PER_HOUR = 20;
    /** Beyond, users idle for an hour are dropped from the title limiter (bounded memory). */
    static final int MAX_TRACKED_USERS = 1_000;
    static final String EMPTY_ARRAY = "[]";

    private final AiConversationRepository repository;
    private final AssistantModel model;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> titleCalls = new ConcurrentHashMap<>();

    private final AssistantChatService chat;

    public AiConversationService(AiConversationRepository repository, AssistantModel model, AssistantChatService chat,
                                 Clock clock) {
        this.repository = repository;
        this.model = model;
        this.chat = chat;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AiConversationSummary> list(UUID userId) {
        return repository.list(userId, LIST_LIMIT);
    }

    /** A new, empty conversation (Bref: messages and history start empty, the app saves them). */
    public AiConversation create(UUID userId, String firstMessage, String title) {
        if (repository.count(userId) >= MAX_PER_USER) { // early refusal, before any model call
            throw limit();
        }
        String resolved = title(userId, firstMessage, title);
        Instant now = clock.instant();
        AiConversation c = new AiConversation(UUID.randomUUID(), userId, resolved, 0, EMPTY_ARRAY, EMPTY_ARRAY, now,
                now);
        if (!repository.insertUnderCap(c, MAX_PER_USER)) {
            throw limit();
        }
        return c;
    }

    @Transactional(readOnly = true)
    public AiConversation get(UUID userId, UUID id) {
        return repository.find(userId, id).orElseThrow(AiConversationService::notFound);
    }

    /** Replaces the messages, the history, the count and the title — each only when given ({@code null} keeps it). */
    @Transactional
    public void save(UUID userId, UUID id, String messages, String aiHistory, Integer messageCount, String title) {
        String newTitle = title == null || title.isBlank() ? null : clean(title);
        if (!repository.update(userId, id, messages, aiHistory, messageCount,
                newTitle == null || newTitle.isEmpty() ? null : newTitle)) {
            throw notFound();
        }
    }

    /** Idempotent, like Bref: an unknown or foreign id deletes nothing. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(userId, id);
    }

    @Transactional
    public void deleteAll(UUID userId, Collection<UUID> ids) {
        repository.deleteAll(userId, ids);
    }

    // ── title ───────────────────────────────────────────────────────────────────────────────────────────

    private String title(UUID userId, String firstMessage, String given) {
        if (given != null && !given.isBlank()) {
            String t = clean(given);
            if (!t.isEmpty()) {
                return t;
            }
        }
        String fallback = fallback(firstMessage);
        // a model title only for a company that may still ask questions (FREE has no AI credit): never metered
        String tenant = com.keevo.shared.infrastructure.persistence.TenantContext.getCurrentTenant();
        if (!model.available() || tenant == null || !takeTitleSlot(userId) || !chat.hasCredit(tenant)) {
            return fallback;
        }
        try {
            String generated = clean(model.title(firstMessage));
            return generated.isEmpty() ? fallback : generated;
        } catch (AssistantModelException | IllegalArgumentException e) {
            log.warn("[AI] title generation failed: {}", e.getClass().getSimpleName());
            return fallback;
        }
    }

    /**
     * At most {@value #TITLES_PER_HOUR} model titles per user and sliding hour. Memory stays bounded: beyond
     * {@value #MAX_TRACKED_USERS} tracked users, those without a call in the last hour are dropped (one lock at a
     * time, never nested).
     */
    private boolean takeTitleSlot(UUID userId) {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(1));
        if (titleCalls.size() > MAX_TRACKED_USERS) {
            titleCalls.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(since);
                }
            });
        }
        Deque<Instant> calls = titleCalls.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekFirst().isBefore(since)) {
                calls.pollFirst();
            }
            if (calls.size() >= TITLES_PER_HOUR) {
                return false;
            }
            calls.addLast(now);
            return true;
        }
    }

    static String fallback(String firstMessage) {
        String t = clean(firstMessage);
        if (t.length() > FALLBACK_TITLE_CHARS) {
            t = t.substring(0, FALLBACK_TITLE_CHARS).strip();
        }
        return t.isEmpty() ? "Nouvelle conversation" : t;
    }

    /** One line, no quotes around, no identifier, at most {@value #MAX_TITLE_CHARS} characters. */
    static String clean(String text) {
        String t = AssistantChatService.sanitize(text == null ? "" : text.replaceAll("[\\r\\n]+", " "));
        t = t.replaceAll("^[\"«“'\\s]+|[\"»”'\\s.]+$", "").strip();
        return t.length() > MAX_TITLE_CHARS ? t.substring(0, MAX_TITLE_CHARS).strip() : t;
    }

    private static DomainException limit() {
        return new DomainException(ErrorCode.AI_CONVERSATION_LIMIT, "conversation cap reached");
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.AI_CONVERSATION_NOT_FOUND, "conversation not found");
    }
}
