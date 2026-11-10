package com.keevo.assistant.conversation.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.conversation.application.service.AiConversationService;
import com.keevo.assistant.conversation.domain.model.AiConversation;
import com.keevo.assistant.conversation.domain.model.AiConversationSummary;
import com.keevo.assistant.module.AiAssistantModule;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.4 — the assistant conversations of the session's user (Bref Point's {@code /v1/ai/conversations}): the
 * app saves the chat (guided topics and free-text questions) after each exchange. Each user sees only their own.
 * Every member role; module {@code ai-assistant} (GET → READ, others → WRITE). Never counted as AI credit.
 */
@RestController
@RequestMapping("/api/v1/ai/conversations")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class AiConversationController {

    /** Bref: at most 500 messages and history items; Keevo also bounds the stored size. */
    static final int MAX_ITEMS = 500;
    static final int MAX_JSON_CHARS = 512 * 1024;

    public record CreateRequest(
            @Size(max = AiConversationService.MAX_TITLE_CHARS, message = "Le titre ne doit pas dépasser 120 caractères")
            String title,
            @NotBlank(message = "Le premier message est obligatoire")
            @Size(max = 2000, message = "Le premier message ne doit pas dépasser 2000 caractères") String firstMessage) {
    }

    public record SaveRequest(
            @Size(max = AiConversationService.MAX_TITLE_CHARS, message = "Le titre ne doit pas dépasser 120 caractères")
            String title,
            @Size(max = MAX_ITEMS, message = "Une conversation ne peut pas dépasser 500 messages") List<JsonNode> messages,
            @Size(max = MAX_ITEMS, message = "L'historique ne peut pas dépasser 500 messages") List<JsonNode> aiHistory,
            @Min(value = 0, message = "Le nombre de messages ne peut pas être négatif")
            @Max(value = 10_000, message = "Le nombre de messages ne doit pas dépasser 10000") Integer messageCount) {
    }

    public record DeleteRequest(
            @NotEmpty(message = "Indiquez au moins une conversation")
            @Size(max = 100, message = "100 conversations au plus à la fois") List<@NotNull UUID> ids) {
    }

    public record Summary(UUID id, String title, int messageCount, Instant createdAt, Instant updatedAt) {
        static Summary of(AiConversationSummary s) {
            return new Summary(s.id(), s.title(), s.messageCount(), s.createdAt(), s.updatedAt());
        }
    }

    public record Conversation(UUID id, String title, int messageCount, @JsonRawValue String messages,
                               @JsonRawValue String aiHistory, Instant createdAt, Instant updatedAt) {
        static Conversation of(AiConversation c) {
            return new Conversation(c.id(), c.title(), c.messageCount(), c.messages(), c.aiHistory(), c.createdAt(),
                    c.updatedAt());
        }
    }

    private final AiConversationService conversations;
    private final ObjectMapper mapper;

    public AiConversationController(AiConversationService conversations, ObjectMapper mapper) {
        this.conversations = conversations;
        this.mapper = mapper;
    }

    /** GET /api/v1/ai/conversations — the 100 most recent, without content. */
    @RequiresEntitlement(value = AiAssistantModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<Summary>>> list() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(conversations.list(CurrentActor.actorId()).stream()
                .map(Summary::of).toList()));
    }

    /**
     * POST /api/v1/ai/conversations — {@code {firstMessage, title?}} → 201, empty conversation; without a title, a
     * short one is generated (never counted). 409 {@code AI_CONVERSATION_LIMIT} beyond 200 conversations.
     */
    @RequiresEntitlement(AiAssistantModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<Conversation>> create(@Valid @RequestBody CreateRequest request) {
        AiConversation c = conversations.create(CurrentActor.actorId(), request.firstMessage(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(Conversation.of(c)));
    }

    /** GET /api/v1/ai/conversations/{id} — 404 {@code AI_CONVERSATION_NOT_FOUND} when unknown or another user's. */
    @RequiresEntitlement(value = AiAssistantModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<Conversation>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(Conversation.of(conversations.get(CurrentActor.actorId(), id))));
    }

    /**
     * PUT /api/v1/ai/conversations/{id} — {@code {messages?, aiHistory?, messageCount?, title?}} replaces the given
     * parts of the saved chat (an absent field keeps its stored value) → 204; 404 {@code AI_CONVERSATION_NOT_FOUND}; 400 beyond 500 items or 512 KB.
     */
    @RequiresEntitlement(AiAssistantModule.ID)
    @PutMapping("/{id}")
    public ResponseEntity<Void> save(@PathVariable UUID id, @Valid @RequestBody SaveRequest request) {
        String messages = json(request.messages(), "messages");
        String history = json(request.aiHistory(), "aiHistory");
        if (length(messages) + length(history) > MAX_JSON_CHARS) {
            throw new InvalidRequestParameterException("messages", "conversation too large",
                    "La conversation est trop volumineuse : commencez-en une nouvelle");
        }
        conversations.save(CurrentActor.actorId(), id, messages, history, request.messageCount(), request.title());
        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/v1/ai/conversations/batch — {@code {ids: [1..100]}} → 204 (another user's ids ignored). */
    @RequiresEntitlement(AiAssistantModule.ID)
    @DeleteMapping("/batch")
    public ResponseEntity<Void> deleteBatch(@Valid @RequestBody DeleteRequest request) {
        conversations.deleteAll(CurrentActor.actorId(), request.ids());
        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/v1/ai/conversations/{id} → 204, idempotent. */
    @RequiresEntitlement(AiAssistantModule.ID)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversations.delete(CurrentActor.actorId(), id);
        return ResponseEntity.noContent().build();
    }

    private static int length(String json) {
        return json == null ? 0 : json.length();
    }

    /** {@code null} (field absent) keeps the stored value. */
    private String json(List<JsonNode> items, String field) {
        if (items == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestParameterException(field, "not serialisable", "Contenu de conversation invalide");
        }
    }
}
