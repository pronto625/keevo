package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientImportPort.ImportedClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("24.3 — ClientImportApiAdapter.check: a client that could not be read back is refused before any insert")
class ClientImportApiAdapterTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");

    private static ImportedClient client(String phone, String type, String niu, String email) {
        return new ImportedClient(UUID.randomUUID(), UUID.randomUUID(), "Awa", phone, email, null, type, niu, null,
                null, null, null, null, null, null, null, T, T);
    }

    @Test
    @DisplayName("valid client passes; non-E.164 phone, unknown type, too long NIU, e-mail without @ refused")
    void check() {
        assertThatCode(() -> ClientImportApiAdapter.check(client("+237699000111", "COMPANY", "M01", "a@b.test")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ClientImportApiAdapter.check(client("699000111", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientImportApiAdapter.check(client("+237699000111", "person", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientImportApiAdapter.check(client("+237699000111", null, "N".repeat(51), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientImportApiAdapter.check(client("+237699000111", null, null, "pas-un-mail")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
