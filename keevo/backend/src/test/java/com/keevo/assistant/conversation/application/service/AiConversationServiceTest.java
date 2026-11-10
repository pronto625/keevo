package com.keevo.assistant.conversation.application.service;

import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import com.keevo.assistant.conversation.domain.model.AiConversation;
import com.keevo.assistant.conversation.domain.port.out.AiConversationRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("21.4 — AiConversationService: titles, caps, not found")
class AiConversationServiceTest {

    static final UUID USER = UUID.randomUUID();

    private AiConversationRepository repo;
    private AssistantModel model;
    private AiConversationService service;
    private com.keevo.assistant.chat.application.service.AssistantChatService chat;
    static final String TENANT = "kv_ttl001";

    @BeforeEach
    void setUp() {
        repo = mock(AiConversationRepository.class);
        model = mock(AssistantModel.class);
        when(model.available()).thenReturn(true);
        chat = mock(com.keevo.assistant.chat.application.service.AssistantChatService.class);
        when(chat.hasCredit(TENANT)).thenReturn(true);
        when(repo.insertUnderCap(any(AiConversation.class), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT);
        service = new AiConversationService(repo, model, chat, Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"),
                ZoneOffset.UTC));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Test
    @DisplayName("given title kept (cleaned); generated title without identifier; model failure → first characters")
    void titles() {
        assertThat(service.create(USER, "Bonjour", "  « Mon point »  ").title()).isEqualTo("Mon point");
        verify(model, never()).title(anyString());

        when(model.title("Quel est mon CA ?")).thenReturn("\"CA du mois 3f2b8c1e-9d4a-4e6b-8c2d-1a2b3c4d5e6f\".");
        assertThat(service.create(USER, "Quel est mon CA ?", null).title()).isEqualTo("CA du mois");

        when(model.title(anyString())).thenThrow(new AssistantModelException("down"));
        String longQuestion = "x".repeat(80);
        assertThat(service.create(USER, longQuestion, null).title()).hasSize(60);
        verify(repo, times(3)).insertUnderCap(any(AiConversation.class), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("model titles limited to 20 per user and hour; disabled model never called")
    void titleLimit() {
        when(model.title(anyString())).thenReturn("Titre");
        for (int i = 0; i < 25; i++) {
            service.create(USER, "Question " + i, null);
        }
        verify(model, times(20)).title(anyString());
        when(model.available()).thenReturn(false);
        assertThat(service.create(UUID.randomUUID(), "Sans modèle", null).title()).isEqualTo("Sans modèle");
        verify(model, times(20)).title(anyString());
        // no AI credit left (FREE plan): never a model title
        when(model.available()).thenReturn(true);
        when(chat.hasCredit(TENANT)).thenReturn(false);
        assertThat(service.create(UUID.randomUUID(), "Offre gratuite", null).title()).isEqualTo("Offre gratuite");
        verify(model, times(20)).title(anyString());
    }

    @Test
    @DisplayName("cap of 200 conversations per user; unknown conversation → AI_CONVERSATION_NOT_FOUND")
    void capsAndNotFound() {
        when(repo.count(USER)).thenReturn(200L);
        assertThatThrownBy(() -> service.create(USER, "Encore", "t")).isInstanceOf(DomainException.class)
                .hasMessageContaining("AI_CONVERSATION_LIMIT");
        when(repo.count(USER)).thenReturn(199L);
        when(repo.insertUnderCap(any(AiConversation.class), org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        assertThatThrownBy(() -> service.create(USER, "Course", "t")).hasMessageContaining("AI_CONVERSATION_LIMIT");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(USER, id)).hasMessageContaining("AI_CONVERSATION_NOT_FOUND");
        when(repo.update(any(), any(), any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.save(USER, id, "[]", "[]", 0, null))
                .hasMessageContaining("AI_CONVERSATION_NOT_FOUND");
    }
}
