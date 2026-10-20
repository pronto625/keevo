package com.keevo.shared.infrastructure.export;

import com.keevo.shared.application.port.AuditPort.AuditEntryRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story v1s-16-20 — readable « Changements » / « Détails » of the audit export. */
@DisplayName("AuditChangesFormatter — before/after as French text (v1s-16-20)")
class AuditChangesFormatterTest {

    private static final String UUID_1 = "7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f";

    @Test
    @DisplayName("price change: amounts in XAF, id keys hidden")
    void priceChange() {
        assertThat(AuditChangesFormatter.changes("{\"price\":1000,\"productId\":\"" + UUID_1 + "\"}",
                "{\"price\":1500}")).isEqualTo("Prix : 1 000 XAF → 1 500 XAF");
    }

    @Test
    @DisplayName("creation (no before snapshot): « Champ : valeur »")
    void creation() {
        assertThat(AuditChangesFormatter.changes(null, "{\"productName\":\"Pagne wax\",\"productSku\":\"PW-1\"}"))
                .isEqualTo("Produit : Pagne wax ; Référence : PW-1");
    }

    @Test
    @DisplayName("codes translated, unknown codes humanized, booleans Oui/Non, signed quantities")
    void codesAndValues() {
        assertThat(AuditChangesFormatter.changes("{\"status\":\"PENDING_VALIDATION\"}", "{\"status\":\"COMPLETED\"}"))
                .isEqualTo("Statut : En attente de validation → Terminé(e)");
        assertThat(AuditChangesFormatter.changes(null, "{\"type\":\"KIOSK\"}")).isEqualTo("Type : Kiosk");
        assertThat(AuditChangesFormatter.changes(null, "{\"movementType\":\"STOCK_ENTRY\",\"quantityChange\":5,"
                + "\"archived\":true}"))
                .isEqualTo("Type de mouvement : Entrée de stock ; Variation : +5 unité(s) ; Archivé : Oui");
        assertThat(AuditChangesFormatter.changes(null, "{\"someFlag\":\"FOO_BAR\"}")).isEqualTo("Some flag : Foo bar");
    }

    @Test
    @DisplayName("nested maps, UUID values and technical keys are dropped; lists counted")
    void dropped() {
        assertThat(AuditChangesFormatter.changes(null, "{\"criteria\":{\"q\":\"x\"},\"datasets\":[\"sales\"],"
                + "\"storeId\":\"" + UUID_1 + "\",\"other\":\"" + UUID_1 + "\",\"nested\":{\"a\":1},"
                + "\"tags\":[1,2,3],\"format\":\"CSV\",\"mode\":\"DIRECT\"}"))
                .isEqualTo("Tags : 3 élément(s) ; Format : CSV ; Mode : Téléchargement direct");
        assertThat(AuditChangesFormatter.changes(null, "[{\"a\":1},{\"a\":2}]")).isEqualTo("Articles : 2 article(s)");
        assertThat(AuditChangesFormatter.changes(null, "{\"fieldsChanged\":[\"firstName\",\"phoneNumber\"]}"))
                .isEqualTo("Champs modifiés : Prénom, Téléphone");
    }

    @Test
    @DisplayName("full snapshots: unchanged fields are skipped; sale ids removed from notes")
    void unchangedSkipped() {
        assertThat(AuditChangesFormatter.changes("{\"name\":\"A\",\"sku\":\"S1\",\"price\":1000}",
                "{\"name\":\"A\",\"sku\":\"S1\",\"price\":1500}"))
                .isEqualTo("Prix : 1 000 XAF → 1 500 XAF");
        assertThat(AuditChangesFormatter.details(
                "{\"notes\":\"Annulation vente " + UUID_1 + "\"}", null))
                .isEqualTo("Motif : Annulation vente");
    }

    @Test
    @DisplayName("v1s-16-23: transfer counterpart store → « Vers » / « Depuis »")
    void transferCounterpart() {
        assertThat(AuditChangesFormatter.details(
                "{\"storeId\":\"" + UUID_1 + "\",\"destinationStoreName\":\"Boutique B\"}", null))
                .isEqualTo("Vers : Boutique B");
        assertThat(AuditChangesFormatter.details("{\"sourceStoreName\":\"Boutique A\"}", null))
                .isEqualTo("Depuis : Boutique A");
    }

    @Test
    @DisplayName("dates in WAT dd/MM/yyyy HH:mm")
    void dates() {
        assertThat(AuditChangesFormatter.changes(null, "{\"from\":\"2026-10-01T00:00:00Z\"}"))
                .isEqualTo("Du : 01/10/2026 01:00");
    }

    @Test
    @DisplayName("malformed JSON gives no text")
    void malformed() {
        assertThat(AuditChangesFormatter.changes("{oops", "not json")).isNull();
        assertThat(AuditChangesFormatter.details("{oops", null)).isNull();
    }

    @Test
    @DisplayName("details: context labels, inventory notes, automatic trigger; ids and actor hidden")
    void details() {
        assertThat(AuditChangesFormatter.details("{\"storeId\":\"" + UUID_1 + "\",\"notes\":\"INVENTORY:" + UUID_1
                + "\",\"actorLabel\":\"Awa\",\"occurredAt\":\"2026-10-01T00:00:00Z\"}", null))
                .isEqualTo("Motif : Ajustement d'inventaire");
        assertThat(AuditChangesFormatter.details(null, "{\"isAutomatic\":true,\"name\":\"x\"}"))
                .isEqualTo("Déclenchement : Automatique");
        assertThat(AuditChangesFormatter.details("{\"ip\":\"1.2.3.4\"}", null)).isNull();
    }

    @Test
    @DisplayName("audit export row: French action/type/source, no id, device or JSON")
    void exportRow() {
        AuditEntryRecord e = new AuditEntryRecord(UUID.randomUUID(), "PriceOverride", UUID.randomUUID(),
                "PRICE_OVERRIDDEN", "{\"price\":1000,\"productId\":\"" + UUID_1 + "\"}", "{\"price\":1500}",
                UUID.randomUUID(), "+237600000000", Instant.parse("2026-10-12T13:32:05Z"), UUID.randomUUID(),
                "Boutique Centre", "Pagne wax", "device-abcdef1234567890", "SYNC", null, "Awa", "EMPLOYEE");
        List<Object> row = AuditLogExportDatasetProvider.row(e);
        assertThat(row).containsExactly(Instant.parse("2026-10-12T13:32:05Z"), "Awa", "Employé",
                "Prix modifié en caisse", "Prix de vente", "Pagne wax", "Boutique Centre", "Synchronisé",
                "Prix : 1 000 XAF → 1 500 XAF", null);
        AuditEntryRecord unknown = new AuditEntryRecord(UUID.randomUUID(), "Gadget", null, "FOO_BAR", null, null,
                null, null, Instant.EPOCH, null, null, null, null, "ONLINE", null, null, null);
        assertThat(AuditLogExportDatasetProvider.row(unknown)).contains("Foo bar", "Gadget");
    }
}
