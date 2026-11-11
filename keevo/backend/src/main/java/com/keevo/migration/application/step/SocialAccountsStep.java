package com.keevo.migration.application.step;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.AuthenticatorOutcome;
import com.keevo.identity.api.LegacyIdentityImportPort.SocialIdentity;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.IdentityResolution;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.AUTHENTICATORS;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.LINKED_USERS;
import static com.keevo.migration.domain.model.MigrationRecord.USERS;

/**
 * Story 24.7 (routed from 19.3 and 24.2) — the Google / Apple identities of Bref accounts ({@code user.socialId} +
 * {@code provider}) become Keevo authenticators ({@code public.user_authenticators}, 19.3) on the Keevo account behind
 * each imported or linked account, so that the owner signs in with the same Google / Apple account on day one. An
 * invited member's identity waits for his acceptance (not imported). One statement and its records in one platform
 * transaction; idempotent (identities already recorded are skipped, the statement inserts only absent ones). A Keevo
 * account that already holds another identity of the same provider keeps it (note, never replaced).
 */
@Component
@Order(55)
class SocialAccountsStep implements ImportStep {

    private final LegacyIdentityImportPort identity;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    SocialAccountsStep(LegacyIdentityImportPort identity, MigrationRecordStore records, MigrationUnitOfWork uow) {
        this.identity = identity;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "comptes Google et Apple";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(AUTHENTICATORS, USERS, LINKED_USERS);
    }

    @Override
    public void run(ImportRun run) {
        List<BrefAccount> accounts = new ArrayList<>();
        accounts.add(run.snapshot().owner());
        run.snapshot().members().stream().filter(m -> !m.deleted()).forEach(accounts::add);
        List<SocialIdentity> identities = new ArrayList<>();
        // Keevo account → Bref account: one each (the check refuses two Bref accounts landing on one Keevo account)
        Map<UUID, BrefAccount> byIdentity = new HashMap<>();
        int alreadyThere = 0;
        for (BrefAccount a : accounts) {
            String provider = BrefMappings.socialProvider(a);
            IdentityResolution.Action action = run.identity(a.id()).action();
            if (provider == null
                    || (action != IdentityResolution.Action.CREATE && action != IdentityResolution.Action.LINK)) {
                continue;
            }
            if (run.imported(BREF_USER, a.id(), AUTHENTICATORS)) {
                alreadyThere++;
                continue;
            }
            UUID account = run.accountId(a.id());
            if (account == null) {
                continue; // not imported (should not happen after the identity and members steps)
            }
            identities.add(new SocialIdentity(account, provider, BrefMappings.trimmed(a.socialId())));
            byIdentity.put(account, a);
        }
        run.count(ImportRun.ALREADY_IMPORTED, alreadyThere);
        if (identities.isEmpty()) {
            return;
        }
        record Imported(List<AuthenticatorOutcome> outcomes, List<MigrationRecord> written) {
        }
        Imported imported = uow.platform(() -> {
            List<AuthenticatorOutcome> outcomes = identity.importAuthenticators(identities);
            List<MigrationRecord> written = new ArrayList<>();
            for (AuthenticatorOutcome o : outcomes) {
                if (o.result() == AuthenticatorOutcome.Result.INSERTED) {
                    BrefAccount a = byIdentity.get(o.identity().userId());
                    written.add(new MigrationRecord(AUTHENTICATORS, o.authenticatorId(), BREF_USER, a.id(),
                            Canonical.sha256(a.canonical()), null));
                }
            }
            records.insert(run.batchId(), run.ownerId(), written);
            return new Imported(outcomes, written);
        });
        run.remember(imported.written()); // after commit
        run.count(ImportRun.AUTHENTICATORS_IMPORTED, imported.written().size());
        for (AuthenticatorOutcome o : imported.outcomes()) {
            switch (o.result()) {
                case ALREADY_LINKED -> run.count(ImportRun.ALREADY_IMPORTED, 1);
                case PROVIDER_ALREADY_LINKED, HELD_BY_OTHER -> run.note(ImportNote.Kind.SOCIAL_NOT_LINKED);
                default -> {
                    // INSERTED: counted above
                }
            }
        }
    }
}
