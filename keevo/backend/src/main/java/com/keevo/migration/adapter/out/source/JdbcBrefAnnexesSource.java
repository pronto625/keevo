package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.6 — {@link BrefAnnexesSourcePort} over the restored Bref database, through the read-only pool and transaction
 * of {@link JdbcBrefSource} (one {@code REPEATABLE READ} read-only transaction for the whole section).
 *
 * <p>Cost per owner: one query telling which tables (and the campaign column of {@code whatsapp_message}) the Bref
 * schema has, then one query per table: the account (unique {@code ownerId}), templates (unique
 * {@code (ownerId, name, language)}), campaigns ({@code IDX_customer_campaign_owner}), their messages
 * ({@code IDX_whatsapp_message_customer_campaign}), the chat ({@code IDX_whatsapp_chat_thread}), notifications and
 * browser subscriptions ({@code userId} indexes); AI conversations have no {@code userId} index in Bref (a scan per owner, as 24.5's unindexed
 * tables — the cutover over every owner is 24.9's). Columns the import does not use are removed in SQL (the platform
 * campaign's {@code campaignId} / {@code userId}).
 */
@Component
class JdbcBrefAnnexesSource implements BrefAnnexesSourcePort {

    static final String TABLES_SQL = "SELECT to_regclass('public.whatsapp_account') IS NOT NULL, "
            + "to_regclass('public.owner_whatsapp_template') IS NOT NULL, "
            + "to_regclass('public.customer_campaign') IS NOT NULL, "
            + "EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = 'whatsapp_message' AND column_name = 'customerCampaignId'), "
            + "to_regclass('public.whatsapp_chat_message') IS NOT NULL, "
            + "to_regclass('public.ai_conversation') IS NOT NULL, "
            + "to_regclass('public.notification') IS NOT NULL, "
            + "to_regclass('public.web_push_subscription') IS NOT NULL";
    /** Browser subscriptions ({@code userId} index); the keys are read (needed to keep the subscription valid). */
    static final String WEB_PUSH_SQL = "SELECT to_jsonb(w)::text FROM web_push_subscription w "
            + "WHERE w.\"userId\" = ANY (?::text[]) ORDER BY w.\"createdAt\", w.id";
    static final String ACCOUNT_SQL = "SELECT to_jsonb(a)::text FROM whatsapp_account a WHERE a.\"ownerId\" = ?::uuid";
    static final String TEMPLATES_SQL = "SELECT to_jsonb(t)::text FROM owner_whatsapp_template t "
            + "WHERE t.\"ownerId\" = ?::uuid ORDER BY t.\"createdAt\", t.id";
    static final String CAMPAIGNS_SQL = "SELECT to_jsonb(c)::text FROM customer_campaign c "
            + "WHERE c.\"ownerId\" = ?::uuid ORDER BY c.\"createdAt\", c.id";
    static final String CAMPAIGN_MESSAGES_SQL = "SELECT (to_jsonb(m) - 'campaignId' - 'userId')::text "
            + "FROM whatsapp_message m WHERE m.\"customerCampaignId\" = ANY (?::uuid[]) ORDER BY m.\"createdAt\", m.id";
    static final String CHAT_SQL = "SELECT to_jsonb(m)::text FROM whatsapp_chat_message m "
            + "WHERE m.\"ownerId\" = ?::uuid ORDER BY m.\"createdAt\", m.id";
    /** {@code userId} is text in Bref: the lower- and upper-case ids (as 24.5's App Store rows). */
    static final String AI_SQL = "SELECT to_jsonb(a)::text FROM ai_conversation a WHERE a.\"userId\" = ANY (?::text[]) "
            + "ORDER BY a.\"createdAt\", a.id";
    static final String NOTIFICATIONS_SQL = "SELECT to_jsonb(n)::text FROM notification n "
            + "WHERE n.\"userId\" = ANY (?::text[]) ORDER BY n.\"createdAt\", n.id";

    private final JdbcBrefSource source;

    JdbcBrefAnnexesSource(JdbcBrefSource source) {
        this.source = source;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.DATABASE;
    }

    @Override
    public BrefAnnexes load(UUID ownerId, Collection<UUID> accountIds) {
        return source.readOnly(c -> read(c, ownerId, accountIds));
    }

    private BrefAnnexes read(Connection c, UUID ownerId, Collection<UUID> accountIds) throws SQLException {
        boolean[] has = tables(c);
        String owner = ownerId.toString();
        WhatsAppAccount account = null;
        if (has[0]) {
            List<WhatsAppAccount> accounts = rows(c, ACCOUNT_SQL, ps -> ps.setString(1, owner),
                    BrefAnnexRows::whatsappAccount);
            account = accounts.isEmpty() ? null : accounts.get(0); // unique (ownerId)
        }
        List<Template> templates = has[1]
                ? rows(c, TEMPLATES_SQL, ps -> ps.setString(1, owner), BrefAnnexRows::template) : List.of();
        List<Campaign> campaigns = has[2]
                ? rows(c, CAMPAIGNS_SQL, ps -> ps.setString(1, owner), BrefAnnexRows::campaign) : List.of();
        List<CampaignMessage> messages = List.of();
        if (has[3] && !campaigns.isEmpty()) {
            Object[] ids = campaigns.stream().map(Campaign::id).toArray();
            messages = rows(c, CAMPAIGN_MESSAGES_SQL, ps -> ps.setArray(1, c.createArrayOf("uuid", ids)),
                    BrefAnnexRows::campaignMessage);
        }
        List<ChatMessage> chat = has[4]
                ? rows(c, CHAT_SQL, ps -> ps.setString(1, owner), BrefAnnexRows::chatMessage) : List.of();
        Object[] users = userKeys(accountIds).toArray();
        List<AiConversation> ai = has[5] && users.length > 0
                ? rows(c, AI_SQL, ps -> ps.setArray(1, c.createArrayOf("text", users)), BrefAnnexRows::aiConversation)
                : List.of();
        List<Notification> notifications = has[6] && users.length > 0
                ? rows(c, NOTIFICATIONS_SQL, ps -> ps.setArray(1, c.createArrayOf("text", users)),
                        BrefAnnexRows::notification)
                : List.of();
        List<BrefAnnexes.WebPushSubscription> webPush = has[7] && users.length > 0
                ? rows(c, WEB_PUSH_SQL, ps -> ps.setArray(1, c.createArrayOf("text", users)),
                        BrefAnnexRows::webPushSubscription)
                : List.of();
        return new BrefAnnexes(account, templates, campaigns, messages, chat, ai, notifications, webPush);
    }

    /** The text keys of the accounts ({@code userId} is text in Bref): lower- and upper-case. */
    static Set<String> userKeys(Collection<UUID> accountIds) {
        Set<String> keys = new LinkedHashSet<>();
        for (UUID id : accountIds) {
            keys.add(id.toString().toLowerCase(Locale.ROOT));
            keys.add(id.toString().toUpperCase(Locale.ROOT));
        }
        return keys;
    }

    private static boolean[] tables(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(TABLES_SQL); ResultSet rs = ps.executeQuery()) {
            rs.next();
            boolean[] has = new boolean[8];
            for (int i = 0; i < has.length; i++) {
                has[i] = rs.getBoolean(i + 1);
            }
            return has;
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private <T> List<T> rows(Connection c, String sql, Binder binder, Function<JsonNode, T> mapper)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.apply(source.jsonRow(rs.getString(1))));
                }
                return rows;
            }
        }
    }
}
