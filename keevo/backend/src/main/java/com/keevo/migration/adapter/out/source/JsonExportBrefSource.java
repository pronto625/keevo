package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Story 24.2 — {@link BrefSourcePort} over the api-bref export of one owner (contract
 * {@code keevo/contracts/bref-export.json}, format 1): {@code owner-<uuid>.json} and its {@code .sha256} (hex SHA-256
 * of the file's exact bytes, optionally followed by the file name as {@code sha256sum} writes it) in the folder
 * {@code keevo.migration.bref.export-dir}.
 *
 * <p>One streaming pass: the sections the import reads (owner, shops, members; products and customers since 24.3) are
 * read, every other section is skipped without being loaded (memory bounded by the owner's rows), the digest is computed on the way, and
 * the snapshot is used only when the digest matches.
 */
@Component
class JsonExportBrefSource implements BrefSourcePort {

    /** The export format this build reads (contract {@code formatVersion}). */
    static final int FORMAT_VERSION = 1;

    private final ObjectMapper mapper;
    private final String exportDir;

    JsonExportBrefSource(ObjectMapper mapper, @Value("${keevo.migration.bref.export-dir:}") String exportDir) {
        this.mapper = mapper;
        this.exportDir = exportDir == null ? "" : exportDir.strip();
    }

    @Override
    public SourceKind kind() {
        return SourceKind.EXPORT;
    }

    @Override
    public boolean available() {
        return !exportDir.isEmpty() && Files.isDirectory(Path.of(exportDir));
    }

    @Override
    public BrefSnapshot load(UUID ownerId) {
        if (!available()) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref export folder not configured");
        }
        Path file = Path.of(exportDir).resolve("owner-%s.json".formatted(ownerId)); // UUID: no path traversal
        if (!Files.isRegularFile(file)) {
            throw new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "No export for this owner");
        }
        String expected = expectedChecksum(file);
        return parse(file, expected, ownerId);
    }

    BrefSnapshot parse(Path file, String expectedChecksum, UUID ownerId) {
        try (InputStream raw = Files.newInputStream(file)) {
            return read(raw, expectedChecksum, ownerId);
        } catch (IOException e) {
            throw invalid("export unreadable");
        }
    }

    /** Parses an export stream, verifying its SHA-256 against {@code expectedChecksum} (lower-case hex). */
    BrefSnapshot read(InputStream raw, String expectedChecksum, UUID ownerId) throws IOException {
        MessageDigest digest = sha256();
        Integer version = null;
        BrefAccount owner = null;
        List<BrefShop> shops = new ArrayList<>();
        List<BrefAccount> members = new ArrayList<>();
        List<BrefProduct> products = new ArrayList<>();
        List<BrefCustomer> customers = new ArrayList<>();
        try (DigestInputStream in = new DigestInputStream(new BufferedInputStream(raw), digest)) {
            JsonParser p = mapper.getFactory().createParser(in);
            p.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw invalid("export is not a JSON object");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                switch (field) {
                    case "formatVersion" -> version = value == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : null;
                    case "owner" -> owner = BrefRows.account(mapper.readTree(p));
                    case "shops" -> readArray(p, value, n -> shops.add(BrefRows.shop(n)));
                    case "members" -> readArray(p, value, n -> members.add(BrefRows.account(n)));
                    case "products" -> readArray(p, value, n -> products.add(BrefRows.product(n)));
                    case "customers" -> readArray(p, value, n -> customers.add(BrefRows.customer(n)));
                    default -> p.skipChildren(); // 24.4–24.6 sections: not read here
                }
            }
            p.close();
            in.transferTo(OutputStream.nullOutputStream()); // the digest covers the whole file
        } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException | DateTimeException e) {
            throw invalid("export malformed");
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expectedChecksum)) {
            throw invalid("export checksum mismatch");
        }
        if (version == null || version != FORMAT_VERSION) {
            throw invalid("unsupported export format version");
        }
        if (owner == null || !owner.id().equals(ownerId) || !BrefMappings.BREF_OWNER_ROLE.equals(owner.role())) {
            throw new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "Export owner mismatch");
        }
        return new BrefSnapshot(owner, shops, members, products, customers, SourceKind.EXPORT);
    }

    private interface NodeConsumer {
        void accept(com.fasterxml.jackson.databind.JsonNode node);
    }

    private void readArray(JsonParser p, JsonToken value, NodeConsumer each) throws IOException {
        if (value != JsonToken.START_ARRAY) {
            throw invalid("section is not an array");
        }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            each.accept(mapper.readTree(p));
        }
    }

    private static String expectedChecksum(Path file) {
        Path sidecar = file.resolveSibling(file.getFileName().toString().concat(".sha256"));
        try {
            String content = Files.readString(sidecar, StandardCharsets.US_ASCII).strip();
            String hex = content.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (!hex.matches("[0-9a-f]{64}")) {
                throw invalid("checksum file malformed");
            }
            return hex;
        } catch (NoSuchFileException e) {
            throw invalid("checksum file missing");
        } catch (IOException e) {
            throw invalid("checksum file unreadable");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.MIGRATION_EXPORT_INVALID, reason);
    }
}
