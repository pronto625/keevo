package com.keevo.migration.application.step;

import com.keevo.assistant.api.AiConversationImportPort;
import com.keevo.assistant.api.AiConversationImportPort.ImportedConversation;
import com.keevo.messaging.api.NotificationImportPort;
import com.keevo.messaging.api.NotificationImportPort.ImportedNotification;
import com.keevo.messaging.api.NotificationImportPort.ImportedWebPush;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.application.ModuleTables;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefAnnexesSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefAnnexPlanner;
import com.keevo.migration.domain.service.BrefAnnexPlanner.Plan;
import com.keevo.migration.domain.service.BrefAnnexPlanner.Row;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.AccountOutcome;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedAccount;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedCampaign;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedMessage;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedRecipient;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort.ImportedTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.AI_CONVERSATIONS;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_SHOP;
import static com.keevo.migration.domain.model.MigrationRecord.NOTIFICATIONS;
import static com.keevo.migration.domain.model.MigrationRecord.STORES;
import static com.keevo.migration.domain.model.MigrationRecord.WEB_PUSH_SUBSCRIPTIONS;
import static com.keevo.migration.domain.model.MigrationRecord.WHATSAPP_ACCOUNTS;
import static com.keevo.migration.domain.model.MigrationRecord.WHATSAPP_CAMPAIGNS;
import static com.keevo.migration.domain.model.MigrationRecord.WHATSAPP_MESSAGES;
import static com.keevo.migration.domain.model.MigrationRecord.WHATSAPP_RECIPIENTS;
import static com.keevo.migration.domain.model.MigrationRecord.WHATSAPP_TEMPLATES;

/**
 * Story 24.6, §10.2 step 10 — the annex data: the WhatsApp Business account (token encrypted by the whatsappcloud
 * module with the Keevo key), the owner's templates, campaigns (never resumed) and recipients, the WhatsApp inbox, the AI
 * conversations and the in-app notifications of the owner and the members — the rows not recorded yet, in ONE tenant
 * transaction with their records (the account row is public, schema-qualified, in the same transaction). The section is
 * read and validated in {@link #check} (before any write of the run), the rules are {@link BrefAnnexPlanner}'s. Nothing
 * is sent, pushed or published.
 *
 * <p>Cost: the section read (see the sources), the plan in memory (O(n log n)); the module tables first (one Flyway
 * history read per module concerned, outside the transaction); then ≤ 2 account statements + 1 statement per tenant
 * table (+ 1 conversations rebuild) and 1 records batch.
 */
@Component
@Order(120)
class AnnexesStep implements ImportStep {

    /** Counter keys of the batch (stable: the dashboard reads them). */
    static final String ACCOUNTS_IMPORTED = "whatsappAccountsImported";
    static final String TEMPLATES_IMPORTED = "whatsappTemplatesImported";
    static final String CAMPAIGNS_IMPORTED = "whatsappCampaignsImported";
    static final String RECIPIENTS_IMPORTED = "whatsappRecipientsImported";
    static final String MESSAGES_IMPORTED = "whatsappMessagesImported";
    static final String CONVERSATIONS_IMPORTED = "aiConversationsImported";
    static final String NOTIFICATIONS_IMPORTED = "notificationsImported";
    static final String WEB_PUSH_IMPORTED = "webPushSubscriptionsImported";

    static final String WHATSAPP_MODULE = "whatsapp-cloud";
    static final String AI_MODULE = "ai-assistant";
    static final String MESSAGING_MODULE = "messaging";

    /** The section the check read, kept for the run. */
    record Loaded(BrefAnnexes section) {
    }

    private final Map<SourceKind, BrefAnnexesSourcePort> sources = new EnumMap<>(SourceKind.class);
    private final LegacyWhatsAppImportPort whatsapp;
    private final AiConversationImportPort conversations;
    private final NotificationImportPort notifications;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final ModuleTables tables;
    private final Clock clock;

    AnnexesStep(List<BrefAnnexesSourcePort> sources, LegacyWhatsAppImportPort whatsapp,
                AiConversationImportPort conversations, NotificationImportPort notifications,
                MigrationRecordStore records, MigrationUnitOfWork uow, ModuleTables tables, Clock clock) {
        sources.forEach(s -> this.sources.put(s.kind(), s));
        this.whatsapp = whatsapp;
        this.conversations = conversations;
        this.notifications = notifications;
        this.records = records;
        this.uow = uow;
        this.tables = tables;
        this.clock = clock;
    }

    @Override
    public String label() {
        return "WhatsApp, assistant et notifications";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(WHATSAPP_ACCOUNTS, WHATSAPP_TEMPLATES, WHATSAPP_CAMPAIGNS, WHATSAPP_RECIPIENTS, WHATSAPP_MESSAGES,
                AI_CONVERSATIONS, NOTIFICATIONS, WEB_PUSH_SUBSCRIPTIONS, STORES, MigrationRecord.USERS, MigrationRecord.LINKED_USERS,
                MigrationRecord.INVITATIONS); // stores and ImportRun.accountId
    }

    @Override
    public List<BlockedCase> check(ImportRun run) {
        return BrefAnnexPlanner.validate(load(run).section(), whatsapp.canStoreTokens());
    }

    private Loaded load(ImportRun run) {
        Loaded loaded = run.attachment(Loaded.class);
        if (loaded == null) {
            BrefAnnexesSourcePort source = sources.get(run.snapshot().source());
            if (source == null) {
                throw new IllegalStateException("No annex source for " + run.snapshot().source());
            }
            Set<UUID> accounts = new LinkedHashSet<>();
            accounts.add(run.ownerId());
            run.snapshot().members().stream().map(BrefAccount::id).forEach(accounts::add);
            loaded = new Loaded(source.load(run.ownerId(), accounts));
            run.attach(Loaded.class, loaded);
        }
        return loaded;
    }

    @Override
    public void run(ImportRun run) {
        for (String key : List.of(ACCOUNTS_IMPORTED, TEMPLATES_IMPORTED, CAMPAIGNS_IMPORTED, RECIPIENTS_IMPORTED,
                MESSAGES_IMPORTED, CONVERSATIONS_IMPORTED, NOTIFICATIONS_IMPORTED, WEB_PUSH_IMPORTED)) {
            run.count(key, 0);
        }
        Plan plan = BrefAnnexPlanner.plan(load(run).section(), lookup(run));
        plan.skipped().forEach(run::note);

        int[] already = {0};
        Row<ImportedAccount> account = plan.account();
        if (account != null && run.imported(account.sourceTable(), account.sourceId(), WHATSAPP_ACCOUNTS)) {
            already[0]++;
            account = null;
        }
        List<Row<ImportedTemplate>> templates = pending(run, plan.templates(), WHATSAPP_TEMPLATES, already);
        List<Row<ImportedCampaign>> campaigns = pending(run, plan.campaigns(), WHATSAPP_CAMPAIGNS, already);
        List<Row<ImportedRecipient>> recipients = pending(run, plan.recipients(), WHATSAPP_RECIPIENTS, already);
        List<Row<ImportedMessage>> messages = pending(run, plan.messages(), WHATSAPP_MESSAGES, already);
        List<Row<ImportedConversation>> ai = pending(run, plan.conversations(), AI_CONVERSATIONS, already);
        List<Row<ImportedNotification>> inbox = pending(run, plan.notifications(), NOTIFICATIONS, already);
        List<Row<ImportedWebPush>> browsers = pending(run, plan.webPush(), WEB_PUSH_SUBSCRIPTIONS, already);
        run.count(ImportRun.ALREADY_IMPORTED, already[0]);
        boolean whatsappRows = account != null || !templates.isEmpty() || !campaigns.isEmpty()
                || !recipients.isEmpty() || !messages.isEmpty();
        if (!whatsappRows && ai.isEmpty() && inbox.isEmpty() && browsers.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        if (whatsappRows) {
            tables.ensure(schema, WHATSAPP_MODULE);
        }
        if (!ai.isEmpty()) {
            tables.ensure(schema, AI_MODULE);
        }
        if (!inbox.isEmpty()) {
            tables.ensure(schema, MESSAGING_MODULE);
        }
        Row<ImportedAccount> accountRow = account;
        record Written(AccountOutcome account, Set<UUID> templates, Set<UUID> campaigns, int recipients,
                       Set<UUID> messages, Set<UUID> conversations, Set<UUID> notifications, Set<UUID> webPush,
                       List<MigrationRecord> records) {
        }
        Written w = uow.tenant(schema, () -> {
            List<MigrationRecord> pending = new ArrayList<>();
            AccountOutcome outcome = null;
            if (accountRow != null) {
                outcome = whatsapp.importAccount(run.tenantId(), accountRow.row());
                if (outcome != AccountOutcome.NUMBER_HELD) {
                    whatsapp.ensureSystemTemplates(schema, accountRow.row().connectedBy(), clock.instant());
                    pending.add(record(accountRow, run.tenantId(), WHATSAPP_ACCOUNTS));
                }
            }
            Set<UUID> t = whatsapp.importTemplates(schema, rows(templates));
            Set<UUID> c = whatsapp.importCampaigns(schema, rows(campaigns));
            int r = whatsapp.importRecipients(schema, rows(recipients));
            Set<UUID> m = whatsapp.importMessages(schema, rows(messages), plan.contactNames());
            Set<UUID> a = conversations.importConversations(schema, rows(ai));
            Set<UUID> n = notifications.importNotifications(schema, rows(inbox));
            Set<UUID> b = notifications.importWebPushSubscriptions(rows(browsers));
            // a row already there (written by a run whose record is missing) has this id: recorded too
            templates.forEach(x -> pending.add(record(x, x.targetId(), WHATSAPP_TEMPLATES)));
            campaigns.forEach(x -> pending.add(record(x, x.targetId(), WHATSAPP_CAMPAIGNS)));
            recipients.forEach(x -> pending.add(record(x, x.targetId(), WHATSAPP_RECIPIENTS)));
            messages.forEach(x -> pending.add(record(x, x.targetId(), WHATSAPP_MESSAGES)));
            ai.forEach(x -> pending.add(record(x, x.targetId(), AI_CONVERSATIONS)));
            inbox.forEach(x -> pending.add(record(x, x.targetId(), NOTIFICATIONS)));
            // a browser endpoint already registered in Keevo is not this row: recorded only when inserted
            browsers.stream().filter(x -> b.contains(x.targetId()))
                    .forEach(x -> pending.add(record(x, x.targetId(), WEB_PUSH_SUBSCRIPTIONS)));
            records.insert(run.batchId(), run.ownerId(), pending);
            return new Written(outcome, t, c, r, m, a, n, b, pending);
        });
        run.remember(w.records()); // after commit

        if (w.account() == AccountOutcome.NUMBER_HELD) {
            run.note(ImportNote.Kind.WHATSAPP_NUMBER_HELD);
        } else if (w.account() == AccountOutcome.INSERTED) {
            run.count(ACCOUNTS_IMPORTED, 1);
            accountRow.notes().forEach(run::note);
        } else if (w.account() == AccountOutcome.ALREADY_PRESENT) {
            run.count(ImportRun.ALREADY_IMPORTED, 1);
        }
        int written = counted(run, templates, w.templates(), TEMPLATES_IMPORTED)
                + counted(run, campaigns, w.campaigns(), CAMPAIGNS_IMPORTED)
                + counted(run, messages, w.messages(), MESSAGES_IMPORTED)
                + counted(run, ai, w.conversations(), CONVERSATIONS_IMPORTED)
                + counted(run, inbox, w.notifications(), NOTIFICATIONS_IMPORTED);
        run.count(WEB_PUSH_IMPORTED, w.webPush().size());
        run.count(RECIPIENTS_IMPORTED, w.recipients());
        if (w.recipients() == recipients.size()) {
            recipients.forEach(x -> x.notes().forEach(run::note));
        }
        int attempted = templates.size() + campaigns.size() + messages.size() + ai.size() + inbox.size();
        run.count(ImportRun.ALREADY_IMPORTED, attempted - written + recipients.size() - w.recipients());
    }

    /** Counts the rows this call inserted and notes them (a row already there is not noted again). */
    private static <T> int counted(ImportRun run, List<Row<T>> rows, Set<UUID> inserted, String key) {
        for (Row<T> r : rows) {
            if (inserted.contains(r.targetId())) {
                r.notes().forEach(run::note);
            }
        }
        run.count(key, inserted.size());
        return inserted.size();
    }

    private static <T> List<Row<T>> pending(ImportRun run, List<Row<T>> rows, String targetTable, int[] already) {
        List<Row<T>> out = new ArrayList<>();
        for (Row<T> r : rows) {
            if (run.imported(r.sourceTable(), r.sourceId(), targetTable)) {
                already[0]++;
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private static <T> List<T> rows(List<Row<T>> rows) {
        return rows.stream().map(Row::row).toList();
    }

    private static MigrationRecord record(Row<?> row, UUID targetId, String targetTable) {
        return new MigrationRecord(targetTable, targetId, row.sourceTable(), row.sourceId(), row.checksum(),
                ProductsStep.note(row.notes()));
    }

    private static BrefAnnexPlanner.Lookup lookup(ImportRun run) {
        Set<UUID> stores = new HashSet<>();
        Map<UUID, String> shopNames = new HashMap<>();
        for (BrefShop s : run.snapshot().shops()) {
            shopNames.put(s.id(), s.name());
            if (run.imported(BREF_SHOP, s.id(), STORES)) {
                stores.add(s.id());
            }
        }
        Map<UUID, BrefAnnexPlanner.Customer> customers = new HashMap<>();
        for (BrefCustomer c : run.snapshot().customers()) {
            customers.put(c.id(), new BrefAnnexPlanner.Customer(c.name(), BrefMappings.uuidOrNull(c.shopId())));
        }
        return new BrefAnnexPlanner.Lookup(DocumentsStep.ownerAccount(run), run::accountId, stores, shopNames,
                customers);
    }
}
