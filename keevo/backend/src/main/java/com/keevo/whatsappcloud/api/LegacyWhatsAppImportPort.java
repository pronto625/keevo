package com.keevo.whatsappcloud.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.6 — writes a Bref owner's WhatsApp Business data into the imported company, as they were in Bref: no Meta
 * call, no sending, no event, no campaign job (an imported campaign never resumes sending). Every method joins the
 * caller's transaction; rows already present are left untouched ({@code ON CONFLICT DO NOTHING}). The values must
 * already meet the tables' rules (lengths, codes): an invalid row throws {@link IllegalArgumentException} and nothing is
 * written.
 */
public interface LegacyWhatsAppImportPort {

    /** Whether the server can encrypt an access token (the Keevo key is configured). */
    boolean canStoreTokens();

    /** Outcome of {@link #importAccount}. */
    enum AccountOutcome {
        /** The account is now the company's (token encrypted with the Keevo key). */
        INSERTED,
        /** The company already has an account (an earlier run, or connected since): left as is. */
        ALREADY_PRESENT,
        /** The number is connected to another live company: not moved. */
        NUMBER_HELD
    }

    /**
     * A WABA as Bref held it. {@link #toString()} never shows the token.
     *
     * @param accessToken Meta's token in clear (Bref stores it so); encrypted here with the company id as context
     */
    record ImportedAccount(String wabaId, String phoneNumberId, String displayPhoneNumber, String verifiedName,
                           String accessToken, UUID connectedBy, Instant connectedAt) {

        public ImportedAccount {
            Objects.requireNonNull(wabaId, "wabaId");
            Objects.requireNonNull(phoneNumberId, "phoneNumberId");
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(connectedAt, "connectedAt");
        }

        @Override
        public String toString() {
            return "ImportedAccount[wabaId=" + wabaId + ", phoneNumberId=" + phoneNumberId + ", token=***]";
        }
    }

    /**
     * Stores the company's WABA ({@code public.whatsapp_accounts}).
     *
     * @throws IllegalStateException no encryption key ({@link #canStoreTokens()} false)
     */
    AccountOutcome importAccount(UUID tenantId, ImportedAccount account);

    /**
     * The system templates Keevo keeps on a connected account ({@code bref_facture}), status {@code PENDING}: Keevo reads
     * Meta's status at the first invoice sending (21.5). Existing rows are kept.
     */
    void ensureSystemTemplates(String tenantSchema, UUID createdBy, Instant at);

    /** An owner template ({@code status} DRAFT | PENDING | APPROVED | REJECTED | PAUSED | DISABLED). */
    record ImportedTemplate(UUID id, String name, String language, String category, String headerFormat,
                            String headerText, String bodyText, List<String> bodyExamples, String footerText,
                            String status, String rejectedReason, String submitError, String metaTemplateId,
                            UUID createdBy, Instant createdAt, Instant updatedAt) {

        public ImportedTemplate {
            Objects.requireNonNull(id, "id");
            bodyExamples = bodyExamples == null ? List.of() : List.copyOf(bodyExamples);
        }
    }

    /** @return the ids inserted by this call */
    Set<UUID> importTemplates(String tenantSchema, List<ImportedTemplate> templates);

    /** A template variable of a campaign ({@code source} STATIC | CLIENT_NAME | STORE_NAME). */
    record Variable(String source, String value) {
    }

    /** A campaign ({@code status} DRAFT | SENT | CANCELLED: never SCHEDULED nor SENDING). */
    record ImportedCampaign(UUID id, String name, String description, String status, List<UUID> storeIds,
                            UUID templateId, List<Variable> variables, String headerMediaLink, String templateName,
                            String templateLanguage, String templateBody, String templateHeader, Instant startedAt,
                            Instant sentAt, int targetedCount, int sentCount, int deliveredCount, int readCount,
                            int failedCount, Integer inactiveForDays, Integer purchasedWithinDays, Long totalSpentMin,
                            Long totalSpentMax, Integer totalInvoicesMin, Integer totalInvoicesMax, UUID createdBy,
                            Instant createdAt, Instant updatedAt) {

        public ImportedCampaign {
            Objects.requireNonNull(id, "id");
            storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /** @return the ids inserted by this call */
    Set<UUID> importCampaigns(String tenantSchema, List<ImportedCampaign> campaigns);

    /** A campaign recipient ({@code phone} digits; {@code status} SENT | FAILED | UNCERTAIN). */
    record ImportedRecipient(UUID campaignId, String phone, UUID clientId, String clientName, String storeName,
                             String status, String waMessageId, String error, Instant updatedAt) {
    }

    /** @return the number of recipients inserted by this call */
    int importRecipients(String tenantSchema, List<ImportedRecipient> recipients);

    /**
     * An inbox message ({@code direction} IN | OUT; {@code status} SENT | DELIVERED | READ | FAILED for OUT, else
     * {@code null}).
     */
    record ImportedMessage(UUID id, String contactPhone, String direction, String type, String body,
                           String waMessageId, String status, Instant readAt, UUID sentBy, Instant createdAt) {

        public ImportedMessage {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Inserts the messages, then rebuilds the conversation row of every contact that got a message (last message,
     * unread count, last inbound instant) from the stored messages.
     *
     * @param contactNames the contact's name per phone (digits), the latest Bref knew
     * @return the ids inserted by this call
     */
    Set<UUID> importMessages(String tenantSchema, List<ImportedMessage> messages, Map<String, String> contactNames);
}
