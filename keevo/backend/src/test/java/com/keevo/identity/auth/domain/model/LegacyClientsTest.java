package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("19.10 — LegacyClients")
class LegacyClientsTest {

    @Test
    @DisplayName("only MANAGER and VIEWER are refused to a legacy client; null / unknown roles never")
    void refusedRoles() {
        assertThat(LegacyClients.refuses("MANAGER")).isTrue();
        assertThat(LegacyClients.refuses("VIEWER")).isTrue();
        for (String role : new String[]{"OWNER", "EMPLOYEE", "SUPER_ADMIN", null, "", "viewer", "GHOST"}) {
            assertThat(LegacyClients.refuses(role)).as(String.valueOf(role)).isFalse();
            assertThatCode(() -> LegacyClients.requireAllowed(true, role)).doesNotThrowAnyException();
        }
        assertThatCode(() -> LegacyClients.requireAllowed(false, "VIEWER")).doesNotThrowAnyException();
        assertThatThrownBy(() -> LegacyClients.requireAllowed(true, "VIEWER"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APP_UPDATE_REQUIRED));
    }
}
