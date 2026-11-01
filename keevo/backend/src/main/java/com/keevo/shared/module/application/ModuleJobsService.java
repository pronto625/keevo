package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.JobFailedException;
import com.keevo.shared.module.ModuleBackfill;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleJobs;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleProvisioner;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ModuleJobs} of the core (Story 16.4): validates the request, then queues the work on {@link AdminJobs}. The
 * backfill keeps its own cross-instance lock (16.5): if another instance holds it, the job fails with the French
 * « déjà en cours » message.
 */
@Service
public class ModuleJobsService implements ModuleJobs {

    static final String BACKFILL_RUNNING =
            "Une migration des modules est déjà en cours. Réessayez dans quelques minutes.";

    private final AdminJobs jobs;
    private final ModuleBackfill backfill;
    private final ModuleProvisioner provisioner;
    private final ModuleCatalog catalog;

    public ModuleJobsService(AdminJobs jobs, ModuleBackfill backfill, ModuleProvisioner provisioner,
                             ModuleCatalog catalog) {
        this.jobs = jobs;
        this.backfill = backfill;
        this.provisioner = provisioner;
        this.catalog = catalog;
    }

    @Override
    public UUID startBackfill(boolean dryRun, UUID actorId) {
        return jobs.start(AdminJobs.Kind.MODULE_BACKFILL, Map.of("dryRun", dryRun), actorId, progress -> {
            try {
                return backfill.run(dryRun, progress);
            } catch (DomainException e) {
                if (ErrorCode.MODULE_BACKFILL_RUNNING.name().equals(e.getDomainCode())) {
                    throw new JobFailedException(BACKFILL_RUNNING);
                }
                throw e;
            }
        });
    }

    @Override
    public UUID startProvisionAll(String moduleId, boolean force, UUID actorId) {
        ModuleManifest module = (moduleId == null ? java.util.Optional.<ModuleManifest>empty()
                : catalog.find(moduleId))
                .filter(m -> m.category() != ModuleCategory.PLATFORM)
                .orElseThrow(() -> new DomainException(ErrorCode.MODULE_NOT_FOUND, "Module introuvable"));
        if (!provisioner.hasMigrations(module.id())) {
            String message = "Le module « " + module.displayName() + " » n'a pas de tables à créer";
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of("moduleId", message));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("moduleId", module.id());
        params.put("module", module.displayName());
        params.put("force", force);
        String id = module.id();
        return jobs.start(AdminJobs.Kind.MODULE_PROVISION_ALL, params, actorId,
                progress -> provisioner.provisionAll(id, force, progress));
    }

    @Override
    public boolean hasMigrations(String moduleId) {
        return moduleId != null && catalog.contains(moduleId) && provisioner.hasMigrations(moduleId);
    }
}
