package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefSnapshot;
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

/** Story 24.4 — the export's {@code invoices} (nested lines and payments) and {@code expenses} sections. */
@DisplayName("24.4 — JSON export source: documents and expenses")
class JsonExportBrefSourceDocumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID OWNER = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000001");
    private static final UUID INVOICE = UUID.fromString("6f1d2c3b-0000-4000-8000-000000000501");

    @TempDir Path dir;
    private JsonExportBrefSource source;
    private ObjectNode example;

    @BeforeEach
    void setUp() throws Exception {
        source = new JsonExportBrefSource(MAPPER, dir.toString());
        example = (ObjectNode) MAPPER.readTree(Files.readString(Path.of("../contracts/bref-export.json")))
                .path("example").deepCopy();
    }

    private void write(JsonNode export) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(export);
        Files.write(dir.resolve("owner-" + OWNER + ".json"), bytes);
        Files.writeString(dir.resolve("owner-" + OWNER + ".json.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("the contract example: one sale with its line (parent id) and instalment, one expense with cents")
    void contractExample() throws Exception {
        write(example);
        BrefSnapshot s = source.load(OWNER);
        BrefInvoice invoice = s.invoices().get(0);
        assertThat(invoice.id()).isEqualTo(INVOICE);
        assertThat(invoice.quote()).isFalse();
        assertThat(invoice.status()).isEqualTo("partial");
        assertThat(invoice.total()).isEqualByComparingTo("25000");
        assertThat(invoice.initialPayment()).isEqualByComparingTo("10000");
        assertThat(invoice.date()).isEqualTo("2026-03-01");
        assertThat(invoice.publicTokenExpiresAt()).isEqualTo(Instant.parse("2026-05-30T10:00:00Z"));
        assertThat(invoice.items()).singleElement().satisfies(i -> {
            assertThat(i.invoiceId()).isEqualTo(INVOICE);
            assertThat(i.quantity()).isEqualByComparingTo("2");
        });
        assertThat(invoice.payments()).singleElement().satisfies(p -> {
            assertThat(p.invoiceId()).isEqualTo(INVOICE);
            assertThat(p.paid()).isFalse();
            assertThat(p.dueDate()).isEqualTo("2026-04-01");
        });
        assertThat(s.expenses()).singleElement().satisfies(e -> {
            assertThat(e.amount()).isEqualByComparingTo("15000.50");
            assertThat(e.expenseDate()).isEqualTo("2026-03-05");
            assertThat(e.category()).isEqualTo("rent");
        });
        // the same row read as numbers instead of decimal strings gives the same canonical form
        ((ObjectNode) example.path("invoices").get(0)).put("total", 25000);
        write(example);
        assertThat(source.load(OWNER).invoices().get(0).canonical()).isEqualTo(invoice.canonical());
    }

    @Test
    @DisplayName("a malformed section (items not an array) is refused like any malformed export")
    void malformed() throws Exception {
        ((ObjectNode) example.path("invoices").get(0)).put("items", "none");
        write(example);
        assertThat(catchThrowableOfType(() -> source.load(OWNER), DomainException.class).getDomainCode())
                .isEqualTo("MIGRATION_EXPORT_INVALID");
    }
}
