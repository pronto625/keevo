"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { JobProgress } from "@/components/modules/JobProgress";
import { PlanProfileCard } from "@/components/modules/PlanProfileCard";
import { updatePlanProfile } from "@/lib/api/admin-plans";
import { consoleErrorMessage, errorCode } from "@/lib/api/console-errors";
import { moduleKeys, useLatestJobs, useModuleCatalogue, usePlanProfiles } from "@/hooks/useModules";
import type { PlanProfileInput, PlanTypeCode } from "@/types/modules";

/**
 * Story 16.4 — « Profils & plafonds »: the limits and paid modules of each offer (Story 16.8 API). A change of the
 * modules re-syncs the accounts of the offer in a background job whose progress is shown here.
 */
export default function PlansPageClient() {
  const queryClient = useQueryClient();
  const profiles = usePlanProfiles();
  const catalogue = useModuleCatalogue();
  const [errors, setErrors] = useState<Partial<Record<PlanTypeCode, string>>>({});
  const [toast, setToast] = useState<string | null>(null);
  // the latest re-sync is shown when the page opens (a reload never loses a running job or its report)
  const latest = useLatestJobs("PROFILE_RESYNC");
  const [startedJob, setJobId] = useState<string | null>(null);
  const jobId = startedJob ?? latest.data?.[0]?.id ?? null;

  const paid = (catalogue.data ?? []).filter((m) => !m.free && m.category !== "core" && m.category !== "platform");

  const save = useMutation({
    mutationFn: (v: { planType: PlanTypeCode; input: PlanProfileInput }) => updatePlanProfile(v.planType, v.input),
    onSuccess: (r, v) => {
      setErrors((e) => ({ ...e, [v.planType]: undefined }));
      void queryClient.invalidateQueries({ queryKey: moduleKeys.profiles });
      setToast(
        r.jobId
          ? `Offre ${r.profile.label} enregistrée : mise à jour des comptes en cours`
          : `Offre ${r.profile.label} enregistrée`,
      );
      if (r.jobId) setJobId(r.jobId);
    },
    onError: (err, v) => {
      setErrors((e) => ({ ...e, [v.planType]: consoleErrorMessage(err) }));
      if (errorCode(err) === "ADMIN_JOB_RUNNING") {
        void latest.refetch().then((r) => setJobId(r.data?.[0]?.id ?? null)); // show the re-sync still running
      }
    },
  });

  return (
    <AdminLayout>
      <div className="space-y-4">
        <h1 className="text-xl font-bold text-gray-800">Profils &amp; plafonds</h1>
        <p className="text-sm text-gray-500">
          Les plafonds s&apos;appliquent dès l&apos;enregistrement, sans redémarrage. Les comptes déjà au-dessus
          d&apos;un plafond abaissé gardent leurs données ; seules les nouvelles créations sont refusées.
        </p>
        {toast && (
          <div role="status" className="text-sm rounded-lg px-3 py-2 bg-green-50 text-green-700 border border-green-200">
            {toast}
          </div>
        )}
        {jobId && <JobProgress key={jobId} jobId={jobId} />}
        {profiles.isLoading && <p className="text-sm text-gray-500">Chargement…</p>}
        {profiles.isError && (
          <p className="text-sm text-red-600">{consoleErrorMessage(profiles.error, "Profils illisibles pour le moment")}</p>
        )}
        {profiles.data &&
          profiles.data.map((p) => (
            <PlanProfileCard
              key={`${p.planType}-${p.maxStores}-${p.maxProducts}-${p.maxEmployees}-${p.modules.map((m) => m.id).join(",")}`}
              profile={p}
              paidModules={paid}
              isPending={save.isPending}
              serverError={errors[p.planType] ?? null}
              onSave={(input) => save.mutate({ planType: p.planType, input })}
            />
          ))}
      </div>
    </AdminLayout>
  );
}
