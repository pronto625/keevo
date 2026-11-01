"use client";

import type { AdminJob, BackfillReport, ProvisioningReport, TenantFailure } from "@/types/modules";

function plural(n: number, one: string, many: string): string {
  return `${n} ${n > 1 ? many : one}`;
}

function Failures({ failures }: { failures: TenantFailure[] }) {
  if (failures.length === 0) return null;
  return (
    <div>
      <p className="text-xs font-medium text-red-700 mb-1">
        {plural(failures.length, "compte en échec", "comptes en échec")}
      </p>
      <ul className="text-xs text-red-700 space-y-0.5 max-h-40 overflow-y-auto">
        {failures.map((f, i) => (
          <li key={`${f.tenantCode}-${i}`}>
            <span className="font-medium">{f.tenantCode}</span> — {f.reason}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Backfill or profile re-sync report (same shape). */
export function BackfillReportView({ report, resync = false }: { report: BackfillReport; resync?: boolean }) {
  return (
    <div className="space-y-2 text-sm text-gray-700">
      {report.dryRun && (
        <p className="text-xs font-medium text-blue-700">Simulation : rien n&apos;a été modifié.</p>
      )}
      <ul className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs">
        <li>{plural(report.tenantsProcessed, "compte traité", "comptes traités")}</li>
        <li>{plural(report.tenantsSkipped, "compte ignoré", "comptes ignorés")}</li>
        <li>{plural(report.rowsInserted, "module ajouté", "modules ajoutés")}</li>
        <li>{plural(report.rowsUpdated, "module mis à jour", "modules mis à jour")}</li>
        <li>{plural(report.rowsUnchanged, "module déjà à jour", "modules déjà à jour")}</li>
        <li>{plural(report.rowsPreserved, "réglage du super-admin conservé", "réglages du super-admin conservés")}</li>
        {!resync && (
          <li>{plural(report.modulesProvisioned, "création de tables", "créations de tables")}</li>
        )}
      </ul>
      {report.tenants.length > 0 && (
        <details>
          <summary className="text-xs text-gray-600 cursor-pointer">
            {plural(report.tenants.length, "compte modifié", "comptes modifiés")}
          </summary>
          <ul className="text-xs text-gray-600 mt-1 space-y-0.5 max-h-40 overflow-y-auto">
            {report.tenants.map((t) => (
              <li key={t.tenantCode}>
                <span className="font-medium">{t.tenantCode}</span> — {t.inserted} ajouté(s), {t.updated} mis à jour
                {t.provisioned.length > 0 ? ` ; tables : ${t.provisioned.join(", ")}` : ""}
              </li>
            ))}
          </ul>
        </details>
      )}
      <Failures failures={report.failures} />
    </div>
  );
}

export function ProvisioningReportView({ report }: { report: ProvisioningReport }) {
  return (
    <div className="space-y-2 text-sm text-gray-700">
      <ul className="flex flex-wrap gap-x-6 gap-y-1 text-xs">
        <li>{plural(report.provisioned.length, "compte provisionné", "comptes provisionnés")}</li>
        <li>{plural(report.upToDate ?? 0, "compte déjà à jour", "comptes déjà à jour")}</li>
        <li>{plural(report.skipped.length, "compte ignoré (module inactif)", "comptes ignorés (module inactif)")}</li>
      </ul>
      <Failures failures={report.failures} />
    </div>
  );
}

/** The final report of a finished job, by kind. */
export function JobReport({ job }: { job: AdminJob }) {
  if (!job.report || typeof job.report !== "object") return null;
  if (job.kind === "MODULE_PROVISION_ALL") return <ProvisioningReportView report={job.report as ProvisioningReport} />;
  return <BackfillReportView report={job.report as BackfillReport} resync={job.kind === "PROFILE_RESYNC"} />;
}
