package com.keevo.migration.application.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ProvisioningResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.TENANT_IMPORTED;

/**
 * Story 24.6, §10.2 step 12 — the end of an import: the tables of every module the company may now use (its plan is
 * the one 24.5 applied) through {@link ModuleProvisioner#provisionActiveModules} (idempotent: history reads when up to
 * date; no notification, no event), then — once per company — the audit entry {@value #ACTION}
 * {@code {source: bref, counts}} in the company's journal, with its record (owner → {@code tenant_imported}) in the same
 * tenant transaction. The counts are those of the run that writes it.
 *
 * <p>Cost: the provisioning (one lock + history reads per installed module), ≤ 1 audit insert + 1 record.
 */
@Component
@Order(140)
class FinalizeStep implements ImportStep {

    static final String ACTION = "TENANT_IMPORTED";
    static final String ENTITY_TYPE = "Tenant";
    static final String ENTITY_LABEL = "Import depuis Bref Point";
    static final String MODULES_PROVISIONED = "modulesProvisioned";

    private final ModuleProvisioner provisioner;
    private final AuditPort audit;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final ObjectMapper json;

    FinalizeStep(ModuleProvisioner provisioner, AuditPort audit, MigrationRecordStore records,
                 MigrationUnitOfWork uow, ObjectMapper json) {
        this.provisioner = provisioner;
        this.audit = audit;
        this.records = records;
        this.uow = uow;
        this.json = json;
    }

    @Override
    public String label() {
        return "modules et journal";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(TENANT_IMPORTED);
    }

    @Override
    public void run(ImportRun run) {
        String schema = run.tenantSchema();
        // never inside a transaction: Flyway commits each module script on its own connection
        List<ProvisioningResult> results = provisioner.provisionActiveModules(schema);
        run.count(MODULES_PROVISIONED, (int) results.stream().filter(r -> r.migrationsApplied() > 0).count());
        if (run.imported(BREF_USER, run.ownerId(), TENANT_IMPORTED)) {
            run.count(ImportRun.ALREADY_IMPORTED, 1);
            return;
        }
        String metadata = metadata(run.counts());
        MigrationRecord record = new MigrationRecord(TENANT_IMPORTED, run.tenantId(), BREF_USER, run.ownerId(),
                Canonical.sha256(run.snapshot().owner().canonical()), null);
        uow.tenant(schema, () -> {
            audit.record(new AuditPort.AuditEntry(run.actorId(), schema, ACTION, ENTITY_TYPE, run.tenantId(), null,
                    null, null, ENTITY_LABEL, null, "SYSTEM", metadata));
            records.insert(run.batchId(), run.ownerId(), List.of(record));
            return null;
        });
        run.remember(List.of(record));
    }

    /** {@code {"source": "bref", "counts": {…}}} — counters sorted by name (a stable text). */
    String metadata(Map<String, Integer> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "bref");
        m.put("counts", new TreeMap<>(counts));
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit metadata not serializable", e);
        }
    }
}
