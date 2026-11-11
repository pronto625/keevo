package com.keevo.migration.application.step;

import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.2 — the name of an imported company. */
@DisplayName("24.2 — IdentityStep.tenantName")
class IdentityStepTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private final UUID owner = UUID.randomUUID();

    private BrefShop shop(String name, Instant created, Instant deleted) {
        return new BrefShop(UUID.randomUUID(), owner, name, "A", "food", null, null, null, null, null, "CM", true,
                created, created, deleted, null);
    }

    private BrefAccount account(String first, String last) {
        return new BrefAccount(owner, "owner", null, false, "+237600000001", true, null, null, first, last, null,
                true, false, "email", null, T, T, null);
    }

    @Test
    @DisplayName("oldest live shop first, then the oldest deleted one, then the owner's name; 100 characters, never "
            + "half a character")
    void tenantName() {
        BrefAccount a = account("Awa", "Test");
        assertThat(IdentityStep.tenantName(List.of(shop("Récente", T.plusSeconds(60), null),
                shop("Ancienne supprimée", T.minusSeconds(60), T), shop("Ancienne", T, null)), a))
                .isEqualTo("Ancienne");
        assertThat(IdentityStep.tenantName(List.of(shop("Fermée", T, T)), a)).isEqualTo("Fermée");
        assertThat(IdentityStep.tenantName(List.of(), a)).isEqualTo("Awa Test");
        assertThat(IdentityStep.tenantName(List.of(), account(null, null))).isNull();
        String emoji = "😀";
        String longName = "x".repeat(99) + emoji + "yyy";
        String cut = IdentityStep.tenantName(List.of(shop(longName, T, null)), a);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(100);
        assertThat(cut).endsWith(emoji);
    }
}
