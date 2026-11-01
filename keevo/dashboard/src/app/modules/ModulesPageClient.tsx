"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { JobProgress } from "@/components/modules/JobProgress";
import { startBackfill, startProvisionAll } from "@/lib/api/admin-modules";
import { consoleErrorMessage, errorCode } from "@/lib/api/console-errors";
import { useLatestJobs, useModuleCatalogue } from "@/hooks/useModules";
import type { CatalogueModule } from "@/types/modules";

/** Tooltip of « Provisionner » (shown only for modules that ship tables). */
const PROVISIONABLE_HINT = "Crée ou met à jour les tables du module pour chaque compte où il est actif.";

/**
 * Story 16.4 — « Modules »: the catalogue (name, gratuit / payant, version) and the bulk operations run as background
 * jobs — the module migration (simulation, then real run) and the provisioning of one module for every account.
 */
export default function ModulesPageClient() {
  const catalogue = useModuleCatalogue();
  // the latest job of each kind is shown when the page opens (a reload never loses a running job or its report)
  const latestBackfill = useLatestJobs("MODULE_BACKFILL");
  const latestProvision = useLatestJobs("MODULE_PROVISION_ALL");
  const [startedBackfill, setBackfillJob] = useState<string | null>(null);
  const [startedProvision, setProvisionJob] = useState<string | null>(null);
  const backfillJob = startedBackfill ?? latestBackfill.data?.[0]?.id ?? null;
  const provisionJob = startedProvision ?? latestProvision.data?.[0]?.id ?? null;

  /** On 409 (a job of the kind already runs): the French message, and that running job is shown. */
  const refused = (err: unknown, latest: typeof latestBackfill, show: (id: string | null) => void) => {
    setError(consoleErrorMessage(err));
    if (errorCode(err) === "ADMIN_JOB_RUNNING") {
      void latest.refetch().then((r) => show(r.data?.[0]?.id ?? null));
    }
  };
  const [error, setError] = useState<string | null>(null);
  const [confirmReal, setConfirmReal] = useState(false);

  const backfill = useMutation({
    mutationFn: (dryRun: boolean) => startBackfill(dryRun),
    onSuccess: (id) => {
      setError(null);
      setConfirmReal(false);
      setBackfillJob(id);
    },
    onError: (err) => refused(err, latestBackfill, setBackfillJob),
  });
  const provision = useMutation({
    mutationFn: (m: CatalogueModule) => startProvisionAll(m.moduleId),
    onSuccess: (id) => {
      setError(null);
      setProvisionJob(id);
    },
    onError: (err) => refused(err, latestProvision, setProvisionJob),
  });

  const modules = (catalogue.data ?? []).filter((m) => m.category !== "platform");

  return (
    <AdminLayout>
      <div className="space-y-6">
        <h1 className="text-xl font-bold text-gray-800">Modules</h1>

        {error && (
          <p role="alert" className="text-sm rounded-lg px-3 py-2 bg-red-50 text-red-700 border border-red-200">
            {error}
          </p>
        )}

        <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
          <h2 className="font-semibold text-gray-900">Migration des modules</h2>
          <p className="text-sm text-gray-500">
            Écrit pour chaque compte les modules correspondant à son offre (essai restant conservé, réglages du
            super-admin inchangés) puis crée les tables manquantes. Lancez d&apos;abord une simulation.
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={backfill.isPending}
              onClick={() => backfill.mutate(true)}
              className="px-3 py-1.5 text-sm rounded-lg font-medium border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              Simuler
            </button>
            {!confirmReal ? (
              <button
                type="button"
                disabled={backfill.isPending}
                onClick={() => setConfirmReal(true)}
                className="px-3 py-1.5 text-sm rounded-lg font-medium bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50"
              >
                Lancer la migration
              </button>
            ) : (
              <span className="inline-flex items-center gap-2 text-sm">
                <span className="text-gray-700">Confirmer la migration réelle ?</span>
                <button
                  type="button"
                  disabled={backfill.isPending}
                  onClick={() => backfill.mutate(false)}
                  className="px-3 py-1.5 text-sm rounded-lg font-medium bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50"
                >
                  Confirmer
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmReal(false)}
                  className="px-3 py-1.5 text-sm rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50"
                >
                  Annuler
                </button>
              </span>
            )}
          </div>
          {backfillJob && <JobProgress key={backfillJob} jobId={backfillJob} />}
        </section>

        <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
          <h2 className="font-semibold text-gray-900">Catalogue</h2>
          {catalogue.isLoading && <p className="text-sm text-gray-500">Chargement…</p>}
          {catalogue.isError && (
            <p className="text-sm text-red-600">{consoleErrorMessage(catalogue.error, "Catalogue illisible pour le moment")}</p>
          )}
          {provisionJob && <JobProgress key={provisionJob} jobId={provisionJob} />}
          {modules.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-200 text-xs text-gray-500 uppercase">
                  <th className="text-left py-2 font-medium">Module</th>
                  <th className="text-left py-2 font-medium">Offre</th>
                  <th className="text-left py-2 font-medium">Version</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {modules.map((m) => (
                  <tr key={m.moduleId}>
                    <td className="py-2 text-gray-900">{m.displayName}</td>
                    <td className="py-2 text-gray-600">{m.category === "core" ? "Socle" : m.free ? "Gratuit" : "Payant"}</td>
                    <td className="py-2 text-gray-600">{m.version}</td>
                    <td className="py-2 text-right">
                      {m.migrations && (
                      <button
                        type="button"
                        title={PROVISIONABLE_HINT}
                        disabled={provision.isPending}
                        onClick={() => provision.mutate(m)}
                        className="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                      >
                        Provisionner
                      </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </AdminLayout>
  );
}
