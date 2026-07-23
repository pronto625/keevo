package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase.SubmitFeedbackCommand;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SubmitFeedbackServiceTest — Unit tests for {@link SubmitFeedbackService} (Story 14.5, FR92).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubmitFeedbackService")
class SubmitFeedbackServiceTest {

    @Mock private FeedbackRepository feedbackRepository;

    private SubmitFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new SubmitFeedbackService(feedbackRepository);
    }

    // ── Priority determination ───────────────────────────────────────────────

    @Test
    @DisplayName("normal description → NORMAL priority")
    void normalDescription_returnsNormalPriority() {
        assertThat(service.determinePriority("L'application est super")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("'bloqué' keyword → HIGH priority (case insensitive)")
    void blockedKeyword_returnsHigh() {
        assertThat(service.determinePriority("Je suis BLOQUÉ sur l'écran d'accueil")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("'erreur critique' keyword → HIGH priority")
    void erreurCritiqueKeyword_returnsHigh() {
        assertThat(service.determinePriority("Erreur Critique lors du paiement")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("'données perdues' keyword → HIGH priority")
    void donneesPerduesKeyword_returnsHigh() {
        assertThat(service.determinePriority("Données Perdues après synchronisation")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("'ne fonctionne pas' keyword → HIGH priority")
    void neFonctionnePasKeyword_returnsHigh() {
        assertThat(service.determinePriority("Le bouton ne fonctionne pas")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("null description → NORMAL")
    void nullDescription_returnsNormal() {
        assertThat(service.determinePriority(null)).isEqualTo("NORMAL");
    }

    // ── Submission ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit saves feedback with correct priority")
    void submit_savesFeedback() {
        var cmd = new SubmitFeedbackCommand("🐛 Signaler un problème",
                "Je suis bloqué", "1.0.0", "android", "/settings",
                "kv_test01", UUID.randomUUID());

        when(feedbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submit(cmd);

        assertThat(result.getPriority()).isEqualTo("HIGH");
        assertThat(result.getType()).isEqualTo("🐛 Signaler un problème");
        verify(feedbackRepository).save(any());
    }
}
