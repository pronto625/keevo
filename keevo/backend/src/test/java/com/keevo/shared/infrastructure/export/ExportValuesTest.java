package com.keevo.shared.infrastructure.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Story v1s-16-20 — readable helpers of the export values (no technical data in a file). */
@DisplayName("ExportValues — humanize, sale reference, slug, technical guard (v1s-16-20)")
class ExportValuesTest {

    @Test
    @DisplayName("an unknown code is humanized, never written raw")
    void unknownCodeHumanized() {
        assertThat(ExportValues.frLabel(Map.of("STORE", "Boutique"), "KIOSK")).isEqualTo("Kiosk");
        assertThat(ExportValues.frLabel(Map.of("STORE", "Boutique"), "STORE")).isEqualTo("Boutique");
        assertThat(ExportValues.frLabel(Map.of(), null)).isNull();
        assertThat(ExportValues.humanize("MOBILE_MONEY")).isEqualTo("Mobile money");
        assertThat(ExportValues.humanize("FOO_BAR")).isEqualTo("Foo bar");
        assertThat(ExportValues.humanize("someKey")).isEqualTo("Some key");
    }

    @Test
    @DisplayName("sale reference: date and time in WAT with seconds, store when known")
    void saleReference() {
        Instant at = Instant.parse("2026-10-12T13:32:05Z");
        assertThat(ExportValues.saleReference(at, "Boutique Centre"))
                .isEqualTo("Vente du 12/10/2026 à 14:32:05 — Boutique Centre");
        assertThat(ExportValues.saleReference(at, null)).isEqualTo("Vente du 12/10/2026 à 14:32:05");
        assertThat(ExportValues.saleReference(at, "  ")).isEqualTo("Vente du 12/10/2026 à 14:32:05");
        assertThat(ExportValues.saleReference(null, "X")).isNull();
    }

    @Test
    @DisplayName("slug: ASCII lowercase file-name part of a French label")
    void slug() {
        assertThat(ExportValues.slug("Journal d'audit")).isEqualTo("journal-d-audit");
        assertThat(ExportValues.slug("Équipe")).isEqualTo("equipe");
        assertThat(ExportValues.slug("Mouvements de stock")).isEqualTo("mouvements-de-stock");
        assertThat(ExportValues.slug("Clôtures")).isEqualTo("clotures");
        assertThat(ExportValues.slug(" — ")).isNull();
        assertThat(ExportValues.slug(null)).isNull();
    }

    @Test
    @DisplayName("inventory validation notes read « Ajustement d'inventaire »")
    void inventoryNotes() {
        assertThat(ExportValues.notes("INVENTORY:7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"))
                .isEqualTo("Ajustement d'inventaire");
        assertThat(ExportValues.notes("Casse")).isEqualTo("Casse");
        assertThat(ExportValues.notes(null)).isNull();
        // any other id is removed: « Annulation vente <uuid> » → « Annulation vente »
        assertThat(ExportValues.notes("Annulation vente 7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"))
                .isEqualTo("Annulation vente");
        assertThat(ExportValues.notes("Correction  vente 7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f  (x)"))
                .isEqualTo("Correction vente (x)");
        assertThat(ExportValues.notes(" 7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f ")).isNull();
    }

    @Test
    @DisplayName("isTechnical flags a UUID, a SNAKE_CASE code and JSON — not French text")
    void technical() {
        assertThat(ExportValues.isTechnical("7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f")).isTrue();
        assertThat(ExportValues.isTechnical("Vente 7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f")).isTrue();
        assertThat(ExportValues.isTechnical("CATEGORY_RENAMED")).isTrue();
        assertThat(ExportValues.isTechnical("{\"price\":1}")).isTrue();
        assertThat(ExportValues.isTechnical("[1,2]")).isTrue();
        assertThat(ExportValues.isTechnical("Catégorie renommée")).isFalse();
        // embedded code or JSON inside a multi-part cell
        assertThat(ExportValues.isTechnical("Statut : PENDING_VALIDATION")).isTrue();
        assertThat(ExportValues.isTechnical("Données : {\"a\":1}")).isTrue();
        assertThat(ExportValues.isTechnical("Liste [\"x\"]")).isTrue();
        assertThat(ExportValues.isTechnical("Prix : 1 000 XAF → 1 500 XAF ; Mode : CSV")).isFalse();
        assertThat(ExportValues.isTechnical("CSV")).isFalse();
        assertThat(ExportValues.isTechnical(null)).isFalse();
    }
}
