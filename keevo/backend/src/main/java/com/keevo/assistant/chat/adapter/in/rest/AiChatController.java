package com.keevo.assistant.chat.adapter.in.rest;

import com.keevo.assistant.chat.application.service.AssistantChatService;
import com.keevo.assistant.chat.application.service.AssistantChatService.HistoryEntry;
import com.keevo.assistant.module.AiAssistantModule;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Story 21.4 — free-text questions to the assistant (Bref Point's {@code POST /v1/ai/chat}): the model answers by
 * calling the AI tools allowed to the session (never SQL); one AI credit (19.8) per answered question, one question at
 * a time per user. Every member role; module {@code ai-assistant} (WRITE: refused in the read-only grace).
 */
@RestController
@RequestMapping("/api/v1/ai")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class AiChatController {

    /** A message of the history (Bref: role user or assistant, at most 2 000 characters). */
    public record HistoryMessage(
            @NotNull @Pattern(regexp = "user|assistant", message = "Le rôle doit être user ou assistant") String role,
            @NotNull @Size(max = AssistantChatService.MAX_MESSAGE_CHARS,
                    message = "Un message de l'historique ne doit pas dépasser 2000 caractères") String content) {
    }

    public record ChatRequest(
            @NotBlank(message = "La question est obligatoire")
            @Size(max = AssistantChatService.MAX_MESSAGE_CHARS,
                    message = "La question ne doit pas dépasser 2000 caractères") String message,
            @Size(max = AssistantChatService.MAX_HISTORY,
                    message = "L'historique ne doit pas dépasser 20 messages") List<@Valid @NotNull HistoryMessage> history) {
    }

    /**
     * @param creditsRemaining AI credits still usable (tightest of the day and month quotas, packs included);
     *                         null when unlimited
     */
    public record ChatResponse(String reply, Long creditsRemaining) {
    }

    private final AssistantChatService chat;

    public AiChatController(AssistantChatService chat) {
        this.chat = chat;
    }

    /**
     * POST /api/v1/ai/chat — {@code {message, history?}} → {@code {reply, creditsRemaining}}. Errors: 400
     * {@code VALIDATION_ERROR}, 403 {@code QUOTA_EXCEEDED} (details of 19.8), 429 {@code AI_QUESTION_IN_PROGRESS},
     * 502 {@code AI_PROVIDER_ERROR}, 503 {@code AI_UNAVAILABLE} / {@code AI_ANSWER_FAILED} — none of them counted.
     */
    @RequiresEntitlement(AiAssistantModule.ID)
    @PostMapping("/chat")
    public ResponseEntity<ApiResponseWrapper<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        List<HistoryEntry> history = request.history() == null ? List.of() : request.history().stream()
                .map(h -> new HistoryEntry(h.role(), h.content())).toList();
        AssistantChatService.Answer answer = chat.ask(TenantContext.getCurrentTenant(), CurrentActor.actorId(),
                CurrentActor.role(), request.message(), history);
        return ResponseEntity.ok(ApiResponseWrapper.ok(new ChatResponse(answer.reply(), answer.creditsRemaining())));
    }
}
