package com.keevo.assistant.guided.adapter.in.rest;

import com.keevo.assistant.chat.application.service.AssistantChatService;
import com.keevo.assistant.guided.application.service.GuidedTopicService;
import com.keevo.assistant.guided.domain.model.AssistantPeriod;
import com.keevo.assistant.guided.domain.model.GuidedAnswer;
import com.keevo.assistant.module.AiAssistantModule;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.4 — Bref Point's guided topics, free (no model, no AI credit): their data come from the AI tools of 21.8,
 * with the REST gates and scopes of each. Every member role; module {@code ai-assistant} (READ).
 */
@RestController
@RequestMapping("/api/v1/ai/topics")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class GuidedTopicController {

    public record Topic(String id, String label, boolean takesPeriod, boolean takesStore, boolean takesCompare,
                        boolean takesSearch) {
    }

    public record Period(String id, String label, String previousLabel) {
    }

    /**
     * @param freeTextAvailable whether free-text questions can be asked (a model provider is configured); the quota
     *                          still applies
     */
    public record Topics(List<Topic> topics, List<Period> periods, boolean freeTextAvailable) {
    }

    private final GuidedTopicService guided;
    private final AssistantChatService chat;

    public GuidedTopicController(GuidedTopicService guided, AssistantChatService chat) {
        this.guided = guided;
        this.chat = chat;
    }

    /** GET /api/v1/ai/topics — the topics this user can open, the periods, and whether free text is available. */
    @RequiresEntitlement(value = AiAssistantModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<Topics>> topics() {
        List<Topic> topics = guided.available().stream().map(t -> new Topic(t.id(), t.label(), t.takesPeriod(),
                t.takesStore(), t.takesCompare(), t.takesSearch())).toList();
        List<Period> periods = Arrays.stream(AssistantPeriod.values())
                .map(p -> new Period(p.id(), p.label(), p.previousLabel())).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(new Topics(topics, periods, chat.available())));
    }

    /**
     * GET /api/v1/ai/topics/{topic}?period=&compare=&storeId=&search= — the topic's figures and rows. 400
     * {@code VALIDATION_ERROR} (unknown topic or period, filter not taken); 403 with the tool's French refusal when
     * nothing of the topic is allowed; 503 {@code AI_ANSWER_FAILED} when a tool fails.
     */
    @RequiresEntitlement(value = AiAssistantModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{topic}")
    public ResponseEntity<ApiResponseWrapper<GuidedAnswer>> topic(
            @PathVariable String topic,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "false") boolean compare,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(guided.answer(topic, period, compare, storeId, search)));
    }
}
