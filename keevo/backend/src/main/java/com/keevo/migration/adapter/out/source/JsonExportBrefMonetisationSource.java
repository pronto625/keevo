package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefMonetisation.AccountCredit;
import com.keevo.migration.domain.model.BrefMonetisation.AppleTransaction;
import com.keevo.migration.domain.model.BrefMonetisation.PlanPaymentRequest;
import com.keevo.migration.domain.model.BrefMonetisation.RevenueCatEvent;
import com.keevo.migration.domain.model.BrefMonetisation.SmsRecharge;
import com.keevo.migration.domain.model.BrefMonetisation.Subscription;
import com.keevo.migration.domain.model.BrefMonetisation.UsageCounter;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Story 24.5 — {@link BrefMonetisationSourcePort} over the owner's export ({@code owner-<uuid>.json} + {@code .sha256},
 * contract {@code keevo/contracts/bref-export.json}): one streaming pass reading only the monetisation sections, the
 * digest verified against the sidecar as {@link JsonExportBrefSource} does (the snapshot of the same run was read from
 * the same file). The rows are filtered like the database source filters them (the owner's rows; recharges of the
 * owner and the members; RevenueCat events of the owner's subject keys), so both sources give the same section.
 */
@Component
class JsonExportBrefMonetisationSource implements BrefMonetisationSourcePort {

    private final ObjectMapper mapper;
    private final String exportDir;

    JsonExportBrefMonetisationSource(ObjectMapper mapper,
                                     @Value("${keevo.migration.bref.export-dir:}") String exportDir) {
        this.mapper = mapper;
        this.exportDir = exportDir == null ? "" : exportDir.strip();
    }

    @Override
    public SourceKind kind() {
        return SourceKind.EXPORT;
    }

    @Override
    public BrefMonetisation load(UUID ownerId, Collection<UUID> accountIds) {
        if (exportDir.isEmpty() || !Files.isDirectory(Path.of(exportDir))) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref export folder not configured");
        }
        Path file = Path.of(exportDir).resolve("owner-%s.json".formatted(ownerId)); // UUID: no path traversal
        if (!Files.isRegularFile(file)) {
            throw new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "No export for this owner");
        }
        String expected = JsonExportBrefSource.expectedChecksum(file);
        try (InputStream raw = Files.newInputStream(file)) {
            return read(raw, expected, ownerId, accountIds);
        } catch (IOException e) {
            throw invalid("export unreadable");
        }
    }

    /** Parses the monetisation sections of an export stream, verifying its SHA-256 (lower-case hex). */
    BrefMonetisation read(InputStream raw, String expectedChecksum, UUID ownerId, Collection<UUID> accountIds)
            throws IOException {
        MessageDigest digest = JsonExportBrefSource.sha256();
        List<Subscription> subscriptions = new ArrayList<>();
        List<PlanPaymentRequest> requests = new ArrayList<>();
        List<AppleTransaction> apple = new ArrayList<>();
        List<RevenueCatEvent> events = new ArrayList<>();
        List<SmsRecharge> recharges = new ArrayList<>();
        List<UsageCounter> counters = new ArrayList<>();
        AccountCredit credit = null;
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
                    case "subscriptions" -> readArray(p, value, BrefMonetisationRows::subscription, subscriptions::add);
                    case "planPaymentRequests" ->
                            readArray(p, value, BrefMonetisationRows::paymentRequest, requests::add);
                    case "appleTransactions" -> readArray(p, value, BrefMonetisationRows::appleTransaction, apple::add);
                    case "revenuecatEvents" -> readArray(p, value, BrefMonetisationRows::revenueCatEvent, events::add);
                    case "smsRechargeRequests" -> readArray(p, value, BrefMonetisationRows::smsRecharge, recharges::add);
                    case "usageCounters" -> readArray(p, value, BrefMonetisationRows::usageCounter, counters::add);
                    case "accountCredit" -> {
                        if (value == JsonToken.START_OBJECT) {
                            credit = BrefMonetisationRows.accountCredit(mapper.readTree(p));
                        } else if (value != JsonToken.VALUE_NULL) {
                            throw invalid("accountCredit is not an object");
                        }
                    }
                    default -> p.skipChildren(); // the other sections: read by their own steps
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
        // the same rows as the database source reads
        String owner = ownerId.toString();
        Set<UUID> accounts = new HashSet<>(accountIds);
        subscriptions.removeIf(s -> !ownerId.equals(s.ownerId()));
        requests.removeIf(r -> !ownerId.equals(r.ownerId()));
        // as the database source: the owner id in lower or upper case (Bref writes it lower-case)
        apple.removeIf(a -> a.ownerId() == null || !(a.ownerId().equals(owner.toLowerCase(java.util.Locale.ROOT))
                || a.ownerId().equals(owner.toUpperCase(java.util.Locale.ROOT))));
        Set<String> subjects = JdbcBrefMonetisationSource.subjectKeys(owner, subscriptions, apple);
        events.removeIf(e -> e.subjectKey() == null || !subjects.contains(e.subjectKey()));
        recharges.removeIf(r -> r.userId() == null || !accounts.contains(r.userId()));
        counters.removeIf(c -> !ownerId.equals(c.ownerId()));
        if (credit != null && !ownerId.equals(credit.ownerId())) {
            credit = null;
        }
        return new BrefMonetisation(subscriptions, requests, apple, events, credit, recharges, counters);
    }

    private <T> void readArray(JsonParser p, JsonToken value, Function<JsonNode, T> mapping, Consumer<T> each)
            throws IOException {
        if (value == JsonToken.VALUE_NULL) {
            return; // a section exported as null: no row
        }
        if (value != JsonToken.START_ARRAY) {
            throw invalid("section is not an array");
        }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            each.accept(mapping.apply(mapper.readTree(p)));
        }
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.MIGRATION_EXPORT_INVALID, reason);
    }
}
