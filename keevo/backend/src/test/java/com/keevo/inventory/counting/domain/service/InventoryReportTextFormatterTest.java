package com.keevo.inventory.counting.domain.service;

import com.keevo.inventory.counting.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReportTextFormatterTest {

    private final InventoryReportTextFormatter formatter = new InventoryReportTextFormatter();

    @Test
    void formatWhatsApp_shouldMatchEmojiFormat() {
        InventoryGapReport report = buildReport(
                List.of(row("Concordant", 0, 0)),
                List.of(row("Surplus", 3, 9000)),
                List.of(row("Shortage", -5, 25000))
        );

        String text = formatter.formatWhatsApp(report, "Simon");

        assertThat(text).contains("📋 Rapport d'inventaire — Boutique Centrale");
        assertThat(text).contains("📅 ");
        assertThat(text).contains("👤 Simon");
        assertThat(text).contains("✅ Concordants : 1 produit");
        assertThat(text).contains("⚠️ Surplus : 1 produit");
        assertThat(text).contains("🔴 Manquants : 1 produit");
    }

    @Test
    void formatWhatsApp_topFiveShortagesOnly() {
        List<InventoryGapRow> shortages = List.of(
                row("A", -5, 50000),
                row("B", -4, 40000),
                row("C", -3, 30000),
                row("D", -2, 20000),
                row("E", -1, 10000),
                row("F", -1, 5000),
                row("G", -1, 3000)
        );
        InventoryGapReport report = buildReport(List.of(), List.of(), shortages);

        String text = formatter.formatWhatsApp(report, "Simon");

        // Count "•" occurrences — should be max 5
        long bulletCount = text.chars().filter(c -> c == '•').count();
        assertThat(bulletCount).isEqualTo(5);
        assertThat(text).contains("• A");
        assertThat(text).contains("• E");
        assertThat(text).doesNotContain("• F");
        assertThat(text).doesNotContain("• G");
    }

    @Test
    void formatWhatsApp_xafFormattedWithSpaces() {
        InventoryGapReport report = buildReport(
                List.of(), List.of(),
                List.of(row("Robe", -5, 47500))
        );

        String text = formatter.formatWhatsApp(report, "Simon");

        assertThat(text).contains("47 500 FCFA");
    }

    @Test
    void formatWhatsApp_emptyShortagesHandled() {
        InventoryGapReport report = buildReport(
                List.of(row("A", 0, 0), row("B", 0, 0)),
                List.of(), List.of()
        );

        String text = formatter.formatWhatsApp(report, "Simon");

        assertThat(text).contains("✅ Concordants : 2 produits");
        assertThat(text).doesNotContain("🔴");
        assertThat(text).doesNotContain("Top manques");
    }

    // ── Helpers ──

    private InventoryGapRow row(String name, int ecart, long gapValueXaf) {
        return new InventoryGapRow(
                UUID.randomUUID(), name, null, null,
                null, null,
                10, 10 + ecart, ecart,
                5000, gapValueXaf
        );
    }

    private InventoryGapReport buildReport(List<InventoryGapRow> concordant,
                                            List<InventoryGapRow> surplus,
                                            List<InventoryGapRow> shortage) {
        int total = concordant.size() + surplus.size() + shortage.size();
        long surplusValue = surplus.stream().mapToLong(InventoryGapRow::gapValueXaf).sum();
        long shortageValue = shortage.stream().mapToLong(InventoryGapRow::gapValueXaf).sum();

        return new InventoryGapReport(
                UUID.randomUUID(), UUID.randomUUID(), "Boutique Centrale",
                InventoryScope.FULL,
                new InventoryGapSummary(total, concordant.size(), surplus.size(), shortage.size(),
                        surplusValue, shortageValue),
                concordant, surplus, shortage,
                UUID.randomUUID(), Instant.now(),
                "IN_PROGRESS"
        );
    }
}
