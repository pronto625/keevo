package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.BrefAnnexes.AiConversation;
import com.keevo.migration.domain.model.BrefAnnexes.Campaign;
import com.keevo.migration.domain.model.BrefAnnexes.CampaignMessage;
import com.keevo.migration.domain.model.BrefAnnexes.ChatMessage;
import com.keevo.migration.domain.model.BrefAnnexes.Notification;
import com.keevo.migration.domain.model.BrefAnnexes.Template;
import com.keevo.migration.domain.model.BrefAnnexes.WhatsAppAccount;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefAnnexesSourcePort;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Story 24.6 — {@link BrefAnnexesSourcePort} over the owner's export ({@code owner-<uuid>.json} + {@code .sha256},
 * contract {@code keevo/contracts/bref-export.json}): one streaming pass reading only the annex sections, the digest
 * verified against the sidecar as {@link JsonExportBrefSource} does. The rows are filtered and ordered like the database
 * source reads them (the owner's account, templates, campaigns and chat; the messages of those campaigns; the AI
 * conversations and notifications of the owner and the members), so both sources give the same section.
 *
 * <p>The export carries the WhatsApp access token in clear, as Bref stores it: the file is protected by the operator
 * (contract note); the token is never logged.
 */
@Component
class JsonExportBrefAnnexesSource implements BrefAnnexesSourcePort {

    private final ObjectMapper mapper;
    private final String exportDir;

    JsonExportBrefAnnexesSource(ObjectMapper mapper, @Value("${keevo.migration.bref.export-dir:}") String exportDir) {
        this.mapper = mapper;
        this.exportDir = exportDir == null ? "" : exportDir.strip();
    }

    @Override
    public SourceKind kind() {
        return SourceKind.EXPORT;
    }

    @Override
    public BrefAnnexes load(UUID ownerId, Collection<UUID> accountIds) {
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

    /** Parses the annex sections of an export stream, verifying its SHA-256 (lower-case hex). */
    BrefAnnexes read(InputStream raw, String expectedChecksum, UUID ownerId, Collection<UUID> accountIds)
            throws IOException {
        MessageDigest digest = JsonExportBrefSource.sha256();
        List<WhatsAppAccount> accounts = new ArrayList<>();
        List<Template> templates = new ArrayList<>();
        List<Campaign> campaigns = new ArrayList<>();
        List<CampaignMessage> messages = new ArrayList<>();
        List<ChatMessage> chat = new ArrayList<>();
        List<AiConversation> ai = new ArrayList<>();
        List<Notification> notifications = new ArrayList<>();
        List<BrefAnnexes.WebPushSubscription> webPush = new ArrayList<>();
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
                    case "whatsappAccount" -> {
                        if (value == JsonToken.START_OBJECT) {
                            accounts.add(BrefAnnexRows.whatsappAccount(mapper.readTree(p)));
                        } else if (value != JsonToken.VALUE_NULL) {
                            throw invalid("whatsappAccount is not an object");
                        }
                    }
                    case "whatsappTemplates" -> readArray(p, value, BrefAnnexRows::template, templates::add);
                    case "customerCampaigns" -> readArray(p, value, BrefAnnexRows::campaign, campaigns::add);
                    case "campaignMessages" -> readArray(p, value, BrefAnnexRows::campaignMessage, messages::add);
                    case "whatsappChatMessages" -> readArray(p, value, BrefAnnexRows::chatMessage, chat::add);
                    case "aiConversations" -> readArray(p, value, BrefAnnexRows::aiConversation, ai::add);
                    case "notifications" -> readArray(p, value, BrefAnnexRows::notification, notifications::add);
                    case "webPushSubscriptions" ->
                            readArray(p, value, BrefAnnexRows::webPushSubscription, webPush::add);
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
        // the same rows, in the same order, as the database source reads
        Set<String> users = JdbcBrefAnnexesSource.userKeys(accountIds);
        WhatsAppAccount account = accounts.stream().filter(a -> ownerId.equals(a.ownerId())).findFirst().orElse(null);
        templates.removeIf(t -> !ownerId.equals(t.ownerId()));
        campaigns.removeIf(c -> !ownerId.equals(c.ownerId()));
        Set<UUID> campaignIds = campaigns.stream().map(Campaign::id).collect(Collectors.toSet());
        messages.removeIf(m -> m.customerCampaignId() == null || !campaignIds.contains(m.customerCampaignId()));
        chat.removeIf(m -> !ownerId.equals(m.ownerId()));
        ai.removeIf(a -> a.userId() == null || !users.contains(a.userId()));
        notifications.removeIf(n -> n.userId() == null || !users.contains(n.userId()));
        templates.sort(Comparator.comparing(Template::createdAt).thenComparing(x -> x.id().toString()));
        campaigns.sort(Comparator.comparing(Campaign::createdAt).thenComparing(x -> x.id().toString()));
        messages.sort(Comparator.comparing(CampaignMessage::createdAt).thenComparing(x -> x.id().toString()));
        chat.sort(Comparator.comparing(ChatMessage::createdAt).thenComparing(x -> x.id().toString()));
        ai.sort(Comparator.comparing(AiConversation::createdAt).thenComparing(x -> x.id().toString()));
        notifications.sort(Comparator.comparing(Notification::createdAt).thenComparing(x -> x.id().toString()));
        webPush.removeIf(w -> w.userId() == null || !users.contains(w.userId()));
        webPush.sort(Comparator.comparing(BrefAnnexes.WebPushSubscription::createdAt)
                .thenComparing(x -> x.id().toString()));
        return new BrefAnnexes(account, templates, campaigns, messages, chat, ai, notifications, webPush);
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
