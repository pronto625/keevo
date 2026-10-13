package com.keevo.feedback.tutorial.application.service;

import com.keevo.feedback.tutorial.domain.model.Tutorial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ListTutorialsService (v1s-14-6, FR7)")
class ListTutorialsServiceTest {

    private static Tutorial tutorial(String id, String url) {
        return new Tutorial(id, "Vendre", "Enregistrer une vente", "desc", 120, url);
    }

    @Test
    @DisplayName("http(s) URLs kept, others blanked, order preserved")
    void sanitizesUrls() {
        var service = new ListTutorialsService(() -> List.of(
                tutorial("a", "https://youtu.be/abc"),
                tutorial("b", "javascript:alert(1)"),
                tutorial("c", ""),
                tutorial("d", "http://example.com/v")));

        List<Tutorial> list = service.list();

        assertThat(list).extracting(Tutorial::id).containsExactly("a", "b", "c", "d");
        assertThat(list).extracting(Tutorial::url)
                .containsExactly("https://youtu.be/abc", "", "", "http://example.com/v");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x.com/v", "file:///etc/passwd", "intent://x", "youtube.com/watch",
            "https://", "not a url", "   "})
    @DisplayName("non-http(s) or host-less URLs → blank")
    void rejectsUnsafe(String url) {
        assertThat(ListTutorialsService.sanitize(url)).isEmpty();
    }

    @Test
    @DisplayName("null → blank; surrounding spaces trimmed")
    void nullAndTrim() {
        assertThat(ListTutorialsService.sanitize(null)).isEmpty();
        assertThat(ListTutorialsService.sanitize("  HTTPS://youtu.be/x  ")).isEqualTo("HTTPS://youtu.be/x");
    }
}
