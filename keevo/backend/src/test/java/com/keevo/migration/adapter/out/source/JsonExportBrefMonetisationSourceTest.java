package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefMonetisation;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 24.5 — the monetisation sections of the export (contract {@code keevo/contracts/bref-export.json}): the
 * example parses, rows of another owner are dropped like the database source drops them, the digest is verified.
 */
@DisplayName("24.5 — JSON export source: monetisation sections")
class JsonExportBrefMonetisationSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACT = Path.of("../contracts/bref-export.json");
    private static final UUID OWNER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000201");

    @TempDir Path dir;
    private JsonExportBrefMonetisationSource source;
    private ObjectNode example;

    @BeforeEach
    void setUp() throws Exception {
        source = new JsonExportBrefMonetisationSource(MAPPER, dir.toString());
        JsonNode contract = MAPPER.readTree(Files.readString(CONTRACT));
        example = (ObjectNode) contract.path("example").deepCopy();
    }

    private void write(JsonNode export, String checksumOverride) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(export);
        Files.write(dir.resolve("owner-" + OWNER + ".json"), bytes);
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(dir.resolve("owner-" + OWNER + ".json.sha256"),
                (checksumOverride == null ? hex : checksumOverride) + "\n", StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("the contract example: subscription, confirmed request, credit and counter read; foreign rows dropped")
    void contractExample() throws Exception {
        ObjectNode foreign = example.withArray("subscriptions").addObject();
        foreign.put("id", UUID.randomUUID().toString()).put("ownerId", UUID.randomUUID().toString())
                .put("plan", "team").put("status", "active").put("createdAt", "2026-11-01T08:00:00.000Z");
        example.withArray("smsRechargeRequests").addObject().put("id", UUID.randomUUID().toString())
                .put("userId", MEMBER.toString()).put("smsQuantity", 100).put("amountDue", 2000)
                .put("status", "completed").put("paymentMethod", "momo").put("phone", "+237600000009");
        example.withArray("revenuecatEvents").addObject().put("id", "ev-other").put("type", "RENEWAL")
                .put("subjectKey", "O-OF-SOMEONE-ELSE").put("eventTimestampMs", 1);
        write(example, null);

        BrefMonetisation m = source.load(OWNER, List.of(OWNER, MEMBER));
        assertThat(m.subscriptions()).singleElement().satisfies(s -> {
            assertThat(s.plan()).isEqualTo("pro");
            assertThat(s.amountPaid()).isEqualTo(3000);
            assertThat(s.endsAt()).isEqualTo(Instant.parse("2026-12-01T08:00:00Z"));
        });
        assertThat(m.paymentRequests()).singleElement().satisfies(r -> {
            assertThat(r.creditApplied()).isEqualTo(500);
            assertThat(r.providerTransactionRef()).isEqualTo("MCP-EXEMPLE-1");
        });
        assertThat(m.accountCredit().amount()).isEqualTo(1750);
        assertThat(m.usageCounters()).singleElement().satisfies(c -> assertThat(c.used()).isEqualTo(12));
        assertThat(m.smsRecharges()).singleElement().satisfies(r -> assertThat(r.smsQuantity()).isEqualTo(100));
        assertThat(m.revenueCatEvents()).isEmpty();
        assertThat(m.smsRecharges().get(0).toString()).doesNotContain("+237600000009");
    }

    @Test
    @DisplayName("checksum mismatch → export invalid")
    void checksum() throws Exception {
        write(example, "0".repeat(64));
        DomainException e = catchThrowableOfType(() -> source.load(OWNER, List.of(OWNER)), DomainException.class);
        assertThat(e.getDomainCode()).isEqualTo("MIGRATION_EXPORT_INVALID");
    }

    @Test
    @DisplayName("an export without the 24.5 sections reads as an empty section")
    void withoutSections() throws Exception {
        for (String section : List.of("subscriptions", "planPaymentRequests", "appleTransactions", "revenuecatEvents",
                "accountCredit", "smsRechargeRequests", "usageCounters")) {
            example.remove(section);
        }
        write(example, null);
        BrefMonetisation m = source.load(OWNER, List.of(OWNER));
        assertThat(m.subscriptions()).isEmpty();
        assertThat(m.accountCredit()).isNull();
    }
}
