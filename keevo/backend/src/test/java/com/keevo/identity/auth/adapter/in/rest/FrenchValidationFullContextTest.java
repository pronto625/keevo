package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 16.14 — with the real configuration ({@code spring.web.locale: fr}, fixed resolver), bean validation answers
 * in French whatever {@code Accept-Language} the client sends, Hibernate Validator defaults included.
 */
@AutoConfigureMockMvc
@DisplayName("VALIDATION_ERROR in French — full context (16.14)")
class FrenchValidationFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("POST /auth/reset-password, blank phone, Accept-Language: en → 422 with French error and details")
    void blankRequiredField_englishClient_frenchError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "", "code": "123456", "newPassword": "newPass1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                // blank → @NotBlank and @Pattern on phoneNumber; sorted by constraint code: NotBlank first
                .andExpect(jsonPath("$.error").value("ne doit pas être vide"))
                .andExpect(jsonPath("$.details.phoneNumber").value("ne doit pas être vide"));
    }
}
