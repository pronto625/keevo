package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 24.6 — the annex sections of the export (contract {@code keevo/contracts/bref-export.json}): the example
 * parses, rows of another owner / user / campaign are dropped like the database source drops them, JSON columns are
 * canonical, a null section reads as empty, the digest is verified, the token never shows in {@code toString}.
 */
@DisplayName("24.6 — JSON export source: annex sections")
class JsonExportBrefAnnexesSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACT = Path.of("../contracts/bref-export.json");
    private static final UUID OWNER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000201");

    @TempDir Path dir;
    private JsonExportBrefAnnexesSource source;
    private ObjectNode example;

    @BeforeEach
    void setUp() throws Exception {
        source = new JsonExportBrefAnnexesSource(MAPPER, dir.toString());
        example = (ObjectNode) MAPPER.readTree(Files.readString(CONTRACT)).path("example").deepCopy();
    }

    private void write(JsonNode export, String checksumOverride) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(export);
        Files.write(dir.resolve("owner-" + OWNER + ".json"), bytes);
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(dir.resolve("owner-" + OWNER + ".json.sha256"),
                (checksumOverride == null ? hex : checksumOverride) + "\n", StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("the contract example: every annex section read; foreign rows dropped; canonical JSON")
    void contractExample() throws Exception {
        example.withArray("whatsappTemplates").addObject().put("id", UUID.randomUUID().toString())
                .put("ownerId", UUID.randomUUID().toString()).put("name", "autre").put("createdAt",
                        "2026-03-02T08:00:00.000Z");
        example.withArray("campaignMessages").addObject().put("id", UUID.randomUUID().toString())
                .put("customerCampaignId", UUID.randomUUID().toString()).put("phone", "237600000009")
                .put("createdAt", "2026-03-03T08:00:00.000Z");
        example.withArray("notifications").addObject().put("id", UUID.randomUUID().toString())
                .put("userId", UUID.randomUUID().toString()).put("type", "system").put("title", "T")
                .put("message", "M").put("createdAt", "2026-03-06T08:00:00.000Z");
        write(example, null);

        BrefAnnexes a = source.load(OWNER, List.of(OWNER, MEMBER));
        assertThat(a.whatsappAccount().phoneNumberId()).isEqualTo("1000000000000002");
        assertThat(a.whatsappAccount().connected()).isTrue();
        assertThat(a.whatsappAccount().toString()).doesNotContain("EAAG-EXEMPLE");
        assertThat(a.templates()).singleElement().satisfies(t -> assertThat(t.bodyExamples()).containsExactly("Awa"));
        assertThat(a.campaigns()).singleElement().satisfies(c -> {
            assertThat(c.targeting().inactiveForDays()).isEqualTo(30L);
            assertThat(c.content().variables()).singleElement()
                    .satisfies(v -> assertThat(v.source()).isEqualTo("customerName"));
            assertThat(c.whatsapp()).startsWith("{\"bodyVariables\""); // keys sorted
        });
        assertThat(a.campaignMessages()).hasSize(1);
        assertThat(a.chatMessages()).singleElement().satisfies(m -> assertThat(m.direction()).isEqualTo("in"));
        assertThat(a.aiConversations()).singleElement()
                .satisfies(c -> assertThat(c.messages()).isEqualTo("[{\"id\":\"m1\",\"role\":\"user\",\"text\":\"Ventes ?\"}]"));
        assertThat(a.notifications()).singleElement().satisfies(n -> {
            assertThat(n.read()).isTrue();
            assertThat(n.metadata()).isEqualTo("{\"amount\":3000}"); // 3000.0 and 3000: one canonical text
        });
        assertThat(a.webPushSubscriptions()).singleElement()
                .satisfies(w -> assertThat(w.endpoint()).isEqualTo("https://push.example.invalid/send/EXEMPLE"));
    }

    @Test
    @DisplayName("null sections and no account read as empty")
    void nullSections() throws Exception {
        for (String s : List.of("whatsappAccount", "whatsappTemplates", "customerCampaigns", "campaignMessages",
                "whatsappChatMessages", "aiConversations", "notifications", "webPushSubscriptions")) {
            example.putNull(s);
        }
        write(example, null);
        BrefAnnexes a = source.load(OWNER, List.of(OWNER));
        assertThat(a.whatsappAccount()).isNull();
        assertThat(a.templates()).isEmpty();
        assertThat(a.notifications()).isEmpty();
    }

    @Test
    @DisplayName("a checksum that does not match → MIGRATION_EXPORT_INVALID")
    void checksumMismatch() throws Exception {
        write(example, "0".repeat(64));
        DomainException e = catchThrowableOfType(() -> source.load(OWNER, List.of(OWNER)), DomainException.class);
        assertThat(e.getDomainCode()).isEqualTo("MIGRATION_EXPORT_INVALID");
    }
}
