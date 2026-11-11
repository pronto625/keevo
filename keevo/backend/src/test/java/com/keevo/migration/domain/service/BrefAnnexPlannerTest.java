package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.BrefAnnexes.AiConversation;
import com.keevo.migration.domain.model.BrefAnnexes.Campaign;
import com.keevo.migration.domain.model.BrefAnnexes.CampaignMessage;
import com.keevo.migration.domain.model.BrefAnnexes.ChatMessage;
import com.keevo.migration.domain.model.BrefAnnexes.Content;
import com.keevo.migration.domain.model.BrefAnnexes.Notification;
import com.keevo.migration.domain.model.BrefAnnexes.Targeting;
import com.keevo.migration.domain.model.BrefAnnexes.Template;
import com.keevo.migration.domain.model.BrefAnnexes.Variable;
import com.keevo.migration.domain.model.BrefAnnexes.WhatsAppAccount;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.service.BrefAnnexPlanner.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.6 — BrefAnnexPlanner (WhatsApp, AI conversations, notifications)")
class BrefAnnexPlannerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID invited = UUID.randomUUID();
    private final UUID shop = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    private BrefAnnexPlanner.Lookup lookup() {
        return new BrefAnnexPlanner.Lookup(owner, id -> id.equals(owner) || id.equals(member) ? id : null,
                Set.of(shop), Map.of(shop, "Boutique"), Map.of(customer, new BrefAnnexPlanner.Customer("Awa", shop)));
    }

    private static BrefAnnexes section(WhatsAppAccount account, List<Template> templates, List<Campaign> campaigns,
                                       List<CampaignMessage> messages, List<ChatMessage> chat,
                                       List<AiConversation> ai, List<Notification> notifications) {
        return new BrefAnnexes(account, templates, campaigns, messages, chat, ai, notifications, List.of());
    }

    private int seq;

    /** Increasing creation instants: the planner orders rows by creation, then id. */
    private Instant next() {
        return T0.plusSeconds(++seq);
    }

    private WhatsAppAccount account(String status, String waba, String token) {
        return new WhatsAppAccount(UUID.randomUUID(), owner, waba, "2222", "+237 6", "Nom", token, status, T0, T0);
    }

    private Template template(String name, String header, String status, String mediaUrl) {
        return new Template(UUID.randomUUID(), owner, name, "MARKETING", "fr", header, null, mediaUrl, "Bonjour {{1}}",
                List.of("Awa"), null, status, "123", null, null, next(), T0);
    }

    private Campaign campaign(String status, Targeting targeting, Content content) {
        return new Campaign(UUID.randomUUID(), owner, "Promo", null, status, "{}", null, targeting, content, 3, 2, 1,
                1, 1, T0.plusSeconds(3600), T0, owner, next(), T0);
    }

    @Test
    @DisplayName("WABA: connected → planned with the clear token; disconnected / unreadable → noted; no key → blocked")
    void account() {
        Plan ok = BrefAnnexPlanner.plan(section(account("connected", "1111", "s3cr3t-value"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()), lookup());
        assertThat(ok.account().row().accessToken()).isEqualTo("s3cr3t-value");
        assertThat(ok.account().row().connectedBy()).isEqualTo(owner);
        assertThat(ok.account().row().toString()).doesNotContain("s3cr3t-value");
        assertThat(ok.account().targetId()).isNull(); // the tenant id, known by the step

        Plan off = BrefAnnexPlanner.plan(section(account("disconnected", "1111", "tok"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()), lookup());
        assertThat(off.account()).isNull();
        assertThat(off.skipped()).containsExactly(Kind.WHATSAPP_ACCOUNT_DISCONNECTED);

        Plan bad = BrefAnnexPlanner.plan(section(account("connected", "abc", "tok"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()), lookup());
        assertThat(bad.account()).isNull();
        assertThat(bad.skipped()).containsExactly(Kind.WHATSAPP_ACCOUNT_UNREADABLE);

        BrefAnnexes connected = section(account("connected", "1111", "tok"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        assertThat(BrefAnnexPlanner.validate(connected, true)).isEmpty();
        List<BlockedCase> cases = BrefAnnexPlanner.validate(connected, false);
        assertThat(cases).extracting(BlockedCase::code).containsExactly("ANNEX_INVALID");
        assertThat(cases.get(0).label()).doesNotContain("tok");
        // a disconnected account never blocks (it is not imported)
        assertThat(BrefAnnexPlanner.validate(section(account("disconnected", "1111", "tok"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()), false)).isEmpty();
        assertThat(account("connected", "1", "secret-token").canonical()).doesNotContain("secret-token");
    }

    @Test
    @DisplayName("templates: kept with status and examples; invalid name and reserved bref_facture noted; draft media "
            + "noted; Meta's in-between status → PENDING")
    void templates() {
        Template approved = template("promo", "NONE", "APPROVED", null);
        Template draftMedia = template("media", "IMAGE", "DRAFT", "https://x.invalid/a.png");
        Template invalid = template("Bad Name", "NONE", "DRAFT", null);
        Template reserved = template("bref_facture", "NONE", "APPROVED", null);
        Template appeal = template("appel", "NONE", "IN_APPEAL", null);
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(approved, draftMedia, invalid, reserved, appeal),
                List.of(), List.of(), List.of(), List.of(), List.of()), lookup());
        assertThat(p.templates()).extracting(r -> r.row().name()).containsExactly("promo", "media", "appel");
        assertThat(p.templates().get(0).row().status()).isEqualTo("APPROVED");
        assertThat(p.templates().get(0).row().bodyExamples()).containsExactly("Awa");
        assertThat(p.templates().get(1).notes()).containsExactly(Kind.WHATSAPP_TEMPLATE_MEDIA_DROPPED);
        assertThat(p.templates().get(2).row().status()).isEqualTo("PENDING");
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.WHATSAPP_TEMPLATE_UNREADABLE,
                Kind.WHATSAPP_TEMPLATE_RESERVED);
    }

    @Test
    @DisplayName("campaigns: sent / draft / scheduled → draft / sending → sent; shops, template, variables, criteria; "
            + "unreadable settings noted")
    void campaigns() {
        Template t = template("promo", "NONE", "APPROVED", null);
        Content content = new Content("promo", "fr", "TEXT", "https://cdn.invalid/a.png", "Bonjour {{1}}",
                List.of(new Variable(null, "customerName", "cliente"), new Variable(null, "shopName", null)));
        Targeting targeting = new Targeting(List.of(shop), 30L, null, 1000L, null, null, null, false);
        Campaign sent = campaign("sent", targeting, content);
        Campaign scheduled = campaign("scheduled", Targeting.NONE, null);
        Campaign sending = campaign("sending", Targeting.NONE, null);
        Campaign odd = campaign("weird", new Targeting(List.of(UUID.randomUUID()), 99_999L, null, null, null, null,
                null, true), new Content("x", "fr", "AUDIO", "http://insecure", null,
                List.of(new Variable(null, "firstName", null))));
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(t), List.of(sent, scheduled, sending, odd), List.of(),
                List.of(), List.of(), List.of()), lookup());
        var s = p.campaigns().get(0).row();
        assertThat(s.status()).isEqualTo("SENT");
        assertThat(s.sentAt()).isEqualTo(T0);
        assertThat(s.storeIds()).containsExactly(shop);
        assertThat(s.templateId()).isEqualTo(t.id());
        assertThat(s.variables()).extracting(v -> v.source() + ":" + v.value())
                .containsExactly("CLIENT_NAME:cliente", "STORE_NAME:null");
        assertThat(s.inactiveForDays()).isEqualTo(30);
        assertThat(s.totalSpentMin()).isEqualTo(1000L);
        assertThat(s.headerMediaLink()).isEqualTo("https://cdn.invalid/a.png");
        assertThat(s.templateHeader()).isEqualTo("TEXT");
        assertThat(s.targetedCount()).isEqualTo(3);
        assertThat(p.campaigns().get(0).notes()).isEmpty();

        assertThat(p.campaigns().get(1).row().status()).isEqualTo("DRAFT");
        assertThat(p.campaigns().get(1).row().sentAt()).isNull();
        assertThat(p.campaigns().get(1).notes()).containsExactly(Kind.CAMPAIGN_SCHEDULE_DROPPED);
        assertThat(p.campaigns().get(2).row().status()).isEqualTo("SENT");
        assertThat(p.campaigns().get(2).notes()).containsExactly(Kind.CAMPAIGN_SENDING_CLOSED);
        var o = p.campaigns().get(3);
        assertThat(o.row().status()).isEqualTo("DRAFT");
        assertThat(o.row().storeIds()).isEmpty();
        assertThat(o.row().inactiveForDays()).isNull();
        assertThat(o.row().headerMediaLink()).isNull();
        assertThat(o.row().templateHeader()).isNull();
        assertThat(o.notes()).containsExactly(Kind.CAMPAIGN_STATUS_UNKNOWN, Kind.CAMPAIGN_FIELD_DROPPED);
    }

    @Test
    @DisplayName("recipients: latest per phone, statuses mapped, unreadable noted, other campaigns ignored")
    void recipients() {
        Campaign c = campaign("sent", Targeting.NONE, null);
        CampaignMessage first = message(c.id(), "+237 690 00 00 01", "sent", T0, customer);
        CampaignMessage later = message(c.id(), "237690000001", "read", T0.plusSeconds(60), customer);
        CampaignMessage failed = message(c.id(), "237690000002", "failed", T0, UUID.randomUUID());
        CampaignMessage pending = message(c.id(), "237690000003", "pending", T0, customer);
        CampaignMessage noPhone = message(c.id(), "12", "sent", T0, customer);
        CampaignMessage other = message(UUID.randomUUID(), "237690000004", "sent", T0, customer);
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(), List.of(c), List.of(first, later, failed, pending,
                noPhone, other), List.of(), List.of(), List.of()), lookup());
        assertThat(p.recipients()).extracting(r -> r.row().phone() + ":" + r.row().status())
                .containsExactlyInAnyOrder("237690000001:SENT", "237690000002:FAILED", "237690000003:UNCERTAIN");
        assertThat(p.recipients()).filteredOn(r -> r.row().phone().equals("237690000001"))
                .singleElement().satisfies(r -> {
                    assertThat(r.sourceId()).isEqualTo(later.id());
                    assertThat(r.row().clientName()).isEqualTo("Awa");
                    assertThat(r.row().storeName()).isEqualTo("Boutique");
                });
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.CAMPAIGN_RECIPIENT_DUPLICATE,
                Kind.CAMPAIGN_RECIPIENT_UNREADABLE);
    }

    private static CampaignMessage message(UUID campaign, String phone, String status, Instant at, UUID customer) {
        return new CampaignMessage(UUID.randomUUID(), campaign, customer, phone, "promo", "wamid." + UUID.randomUUID(),
                status, "failed".equals(status) ? "Erreur" : null, at, at);
    }

    @Test
    @DisplayName("inbox: directions, synthetic Meta id, duplicates and bad phones noted, latest contact name, sender "
            + "mapped")
    void inbox() {
        ChatMessage in = chat("237690000001", "Awa", "in", "wamid.1", null, null, T0);
        ChatMessage out = chat("237690000001", null, "out", "wamid.2", "read", member, T0.plusSeconds(10));
        ChatMessage noId = chat("237690000002", "Paul", "in", null, null, null, T0.plusSeconds(20));
        ChatMessage dup = chat("237690000001", "Awa B.", "in", "wamid.1", null, null, T0.plusSeconds(30));
        ChatMessage bad = chat("12", null, "in", "wamid.3", null, null, T0);
        ChatMessage odd = chat("237690000001", null, "sideways", "wamid.4", null, null, T0);
        ChatMessage byInvited = chat("237690000001", null, "out", "wamid.5", "weird", invited, T0.plusSeconds(40));
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(), List.of(), List.of(), List.of(in, out, noId, dup, bad,
                odd, byInvited), List.of(), List.of()), lookup());
        assertThat(p.messages()).extracting(r -> r.row().waMessageId())
                .containsExactly("wamid.1", "wamid.2", "bref:" + noId.id(), "wamid.5");
        assertThat(p.messages().get(1).row().status()).isEqualTo("READ");
        assertThat(p.messages().get(1).row().sentBy()).isEqualTo(member);
        assertThat(p.messages().get(3).row().sentBy()).isNull();
        assertThat(p.messages().get(3).row().status()).isNull();
        assertThat(p.messages().get(0).row().direction()).isEqualTo("IN");
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.CHAT_MESSAGE_DUPLICATE, Kind.CHAT_MESSAGE_UNREADABLE,
                Kind.CHAT_MESSAGE_UNREADABLE);
        // the duplicate is skipped before its name is read: the latest written row's name
        assertThat(p.contactNames()).containsEntry("237690000001", "Awa").containsEntry("237690000002", "Paul");
    }

    private static ChatMessage chat(String phone, String name, String direction, String waId, String status,
                                    UUID sentBy, Instant at) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), phone, name, direction, "text", "Bonjour", waId,
                status, null, sentBy, at);
    }

    @Test
    @DisplayName("AI conversations: mapped account, invited or unreadable noted, 200 most recent per account")
    void conversations() {
        List<AiConversation> list = new ArrayList<>();
        for (int i = 0; i < 202; i++) {
            list.add(new AiConversation(UUID.randomUUID(), owner.toString(), "Titre " + i, 2, "[]", "[]",
                    T0.plusSeconds(i), T0.plusSeconds(i)));
        }
        AiConversation ofMember = new AiConversation(UUID.randomUUID(), member.toString().toUpperCase(), "M", 1,
                "[{\"role\":\"user\"}]", "[]", T0, T0);
        AiConversation ofInvited = new AiConversation(UUID.randomUUID(), invited.toString(), "I", 1, "[]", "[]", T0,
                T0);
        AiConversation broken = new AiConversation(UUID.randomUUID(), owner.toString(), "B", 1, "{}", "[]", T0, T0);
        list.add(ofMember);
        list.add(ofInvited);
        list.add(broken);
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(), List.of(), List.of(), List.of(), list, List.of()),
                lookup());
        assertThat(p.conversations()).hasSize(201);
        assertThat(p.conversations()).filteredOn(r -> r.row().userId().equals(owner)).hasSize(200)
                .noneMatch(r -> r.row().title().equals("Titre 0") || r.row().title().equals("Titre 1"));
        assertThat(p.conversations()).filteredOn(r -> r.row().userId().equals(member)).singleElement()
                .satisfies(r -> assertThat(r.row().messages()).isEqualTo("[{\"role\":\"user\"}]"));
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.AI_CONVERSATION_OVER_LIMIT,
                Kind.AI_CONVERSATION_OVER_LIMIT, Kind.AI_CONVERSATION_USER_UNKNOWN, Kind.AI_CONVERSATION_UNREADABLE);
    }

    @Test
    @DisplayName("notifications: read state from updatedAt, metadata kept, long texts shortened, unknown user noted")
    void notifications() {
        Notification read = new Notification(UUID.randomUUID(), owner.toString(), "payment", "push", "Payé",
                "x".repeat(1200), true, "{\"invoiceId\":\"a\"}", T0, T0.plusSeconds(99));
        Notification unread = new Notification(UUID.randomUUID(), member.toString(), "system", "in_app", "Bienvenue",
                "Texte", false, null, T0, T0);
        Notification ofInvited = new Notification(UUID.randomUUID(), invited.toString(), "system", "push", "T", "M",
                false, null, T0, T0);
        Notification empty = new Notification(UUID.randomUUID(), owner.toString(), " ", "push", "T", "M", false, null,
                T0, T0);
        Plan p = BrefAnnexPlanner.plan(section(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(read, unread, ofInvited, empty)), lookup());
        assertThat(p.notifications()).hasSize(2);
        var r = p.notifications().stream().filter(x -> x.sourceId().equals(read.id())).findFirst().orElseThrow();
        assertThat(r.row().readAt()).isEqualTo(T0.plusSeconds(99));
        assertThat(r.row().body()).hasSize(1000);
        assertThat(r.row().data()).isEqualTo("{\"invoiceId\":\"a\"}");
        assertThat(r.notes()).containsExactly(Kind.NOTIFICATION_SHORTENED);
        var u = p.notifications().stream().filter(x -> x.sourceId().equals(unread.id())).findFirst().orElseThrow();
        assertThat(u.row().readAt()).isNull();
        assertThat(u.row().userId()).isEqualTo(member);
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.NOTIFICATION_USER_UNKNOWN,
                Kind.NOTIFICATION_UNREADABLE);
    }

    @Test
    @DisplayName("browser subscriptions: mapped account, unknown user / unreadable noted, keys never in toString")
    void webPush() {
        BrefAnnexes.WebPushSubscription ok = new BrefAnnexes.WebPushSubscription(UUID.randomUUID(), owner.toString(),
                "https://push.invalid/a", "p256", "auth-secret", "UA", T0, null);
        BrefAnnexes.WebPushSubscription ofInvited = new BrefAnnexes.WebPushSubscription(UUID.randomUUID(),
                invited.toString(), "https://push.invalid/b", "p256", "auth", null, T0, T0);
        BrefAnnexes.WebPushSubscription bad = new BrefAnnexes.WebPushSubscription(UUID.randomUUID(), owner.toString(),
                "http://push.invalid/c", "p256", "auth", null, T0, T0);
        Plan p = BrefAnnexPlanner.plan(new BrefAnnexes(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(ok, ofInvited, bad)), lookup());
        assertThat(p.webPush()).singleElement().satisfies(r -> {
            assertThat(r.row().userId()).isEqualTo(owner);
            assertThat(r.row().updatedAt()).isEqualTo(T0);
            assertThat(r.row().toString()).doesNotContain("auth-secret");
        });
        assertThat(ok.canonical()).doesNotContain("auth-secret");
        assertThat(p.skipped()).containsExactlyInAnyOrder(Kind.WEB_PUSH_USER_UNKNOWN, Kind.WEB_PUSH_UNREADABLE);
    }
}
