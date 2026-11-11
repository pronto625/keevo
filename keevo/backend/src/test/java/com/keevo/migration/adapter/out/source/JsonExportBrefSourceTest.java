package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 24.2 — the export adapter against the contract {@code keevo/contracts/bref-export.json}: its example parses into
 * the snapshot (sections of later stories skipped), and every refusal (checksum, sidecar, version, owner, file).
 */
@DisplayName("24.2 — JSON export source (contract bref-export.json)")
class JsonExportBrefSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACT = Path.of("../contracts/bref-export.json");
    private static final UUID OWNER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000001");

    @TempDir Path dir;
    private JsonExportBrefSource source;
    private ObjectNode example;

    @BeforeEach
    void setUp() throws Exception {
        source = new JsonExportBrefSource(MAPPER, dir.toString());
        JsonNode contract = MAPPER.readTree(Files.readString(CONTRACT));
        assertThat(contract.path("formatVersion").asInt()).isEqualTo(JsonExportBrefSource.FORMAT_VERSION);
        example = (ObjectNode) contract.path("example").deepCopy();
    }

    private void write(JsonNode export, String checksumOverride) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(export);
        Files.write(dir.resolve("owner-" + OWNER + ".json"), bytes);
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(dir.resolve("owner-" + OWNER + ".json.sha256"),
                (checksumOverride == null ? hex.toUpperCase() : checksumOverride) + "  owner.json\n",
                StandardCharsets.US_ASCII);
    }

    private String refusal() {
        return catchThrowableOfType(() -> source.load(OWNER), DomainException.class).getDomainCode();
    }

    @Test
    @DisplayName("the contract example: owner, shops, members read; other sections skipped; instants in UTC ms")
    void contractExample() throws Exception {
        example.putArray("whatsapp").addObject().put("anything", "skipped");
        write(example, null);
        assertThat(source.available()).isTrue();
        BrefSnapshot s = source.load(OWNER);
        assertThat(s.source()).isEqualTo(SourceKind.EXPORT);
        assertThat(s.owner().id()).isEqualTo(OWNER);
        assertThat(s.owner().role()).isEqualTo("owner");
        assertThat(s.owner().emailVerified()).isTrue();
        assertThat(s.owner().passwordHash()).isNull();
        assertThat(s.owner().createdAt()).isEqualTo(Instant.parse("2026-01-05T09:00:00Z"));
        assertThat(s.shops()).singleElement().satisfies(shop -> {
            assertThat(shop.category()).isEqualTo("electronics");
            assertThat(shop.ownerId()).isEqualTo(OWNER);
            assertThat(shop.photo()).isNull();
        });
        assertThat(s.members()).singleElement().satisfies(m -> {
            assertThat(m.role()).isEqualTo("admin");
            assertThat(m.passwordHash()).startsWith("$2b$10$");
            assertThat(m.mustChangePassword()).isTrue();
            assertThat(m.lastName()).isNull();
        });
    }

    @Test
    @DisplayName("refusals: checksum, missing sidecar, unknown version, other owner, malformed JSON, no file, no folder")
    void refusals() throws Exception {
        write(example, "0".repeat(64));
        assertThat(refusal()).isEqualTo("MIGRATION_EXPORT_INVALID");

        write(example, null);
        Files.delete(dir.resolve("owner-" + OWNER + ".json.sha256"));
        assertThat(refusal()).isEqualTo("MIGRATION_EXPORT_INVALID");

        ObjectNode v2 = example.deepCopy();
        v2.put("formatVersion", 2);
        write(v2, null);
        assertThat(refusal()).isEqualTo("MIGRATION_EXPORT_INVALID");

        ObjectNode member = example.deepCopy();
        ((ObjectNode) member.path("owner")).put("role", "admin");
        write(member, null);
        assertThat(refusal()).isEqualTo("MIGRATION_OWNER_NOT_FOUND");

        ObjectNode broken = example.deepCopy();
        ((ObjectNode) broken.path("owner")).put("createdAt", "hier");
        write(broken, null);
        assertThat(refusal()).isEqualTo("MIGRATION_EXPORT_INVALID");

        Files.delete(dir.resolve("owner-" + OWNER + ".json"));
        assertThat(refusal()).isEqualTo("MIGRATION_OWNER_NOT_FOUND");

        JsonExportBrefSource none = new JsonExportBrefSource(MAPPER, "");
        assertThat(none.available()).isFalse();
        assertThat(catchThrowableOfType(() -> none.load(OWNER), DomainException.class).getDomainCode())
                .isEqualTo("MIGRATION_SOURCE_UNAVAILABLE");
    }
}
