"use client";

import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useJob } from "@/hooks/useModules";
import { isJobRunning } from "@/lib/api/admin-jobs";
import { jobStatusLabel } from "@/lib/labels";
import type { AdminJob } from "@/types/modules";
import { JobReport } from "./JobReport";

interface JobProgressProps {
  jobId: string;
  /** Called once when the job ends (succeeded or failed). */
  onFinished?: (job: AdminJob) => void;
}

/** Percentage of a job's progress (0 when the total is not known yet). */
export function jobPercent(job: Pick<AdminJob, "processed" | "total" | "status">): number {
  if (job.status === "SUCCEEDED") return 100;
  if (!job.total) return 0;
  return Math.min(100, Math.round((job.processed / job.total) * 100));
}

/**
 * Story 16.4 — a background job followed live: status, progress bar « x / y comptes », then the final report or the
 * French error. Polls the job every second while it runs.
 */
export function JobProgress({ jobId, onFinished }: JobProgressProps) {
  const { data: job, isError, stopped } = useJob(jobId);
  const queryClient = useQueryClient();
  const notified = useRef(false);

  useEffect(() => {
    if (job && !isJobRunning(job) && !notified.current) {
      notified.current = true;
      // what a job changes: profiles (re-sync), catalogue / tenants' modules (backfill, provisioning), job lists
      for (const key of ["plan-profiles", "module-catalogue", "tenant-modules", "admin-jobs"]) {
        void queryClient.invalidateQueries({ queryKey: [key] });
      }
      onFinished?.(job);
    }
  }, [job, onFinished, queryClient]);

  if (stopped || (isError && !job)) {
    return (
      <p role="alert" className="text-sm text-red-600">
        Impossible de suivre la tâche pour le moment. Rechargez la page pour réessayer.
      </p>
    );
  }
  if (!job) {
    return <p className="text-sm text-gray-500">Démarrage de la tâche…</p>;
  }
  const percent = jobPercent(job);
  return (
    <div className="space-y-2 rounded-lg border border-gray-200 bg-gray-50 p-3">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium text-gray-800">{job.label}</span>
        <span
          className={`text-xs px-2 py-0.5 rounded font-medium ${
            job.status === "SUCCEEDED"
              ? "bg-green-100 text-green-800"
              : job.status === "FAILED"
              ? "bg-red-100 text-red-800"
              : "bg-blue-100 text-blue-800"
          }`}
        >
          {jobStatusLabel(job.status)}
        </span>
      </div>
      {job.status !== "FAILED" && (
        <>
          <div
            role="progressbar"
            aria-label="Progression de la tâche"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            className="h-2 w-full rounded bg-gray-200 overflow-hidden"
          >
            <div className="h-2 bg-purple-600 transition-all" style={{ width: `${percent}%` }} />
          </div>
          <p className="text-xs text-gray-500">
            {job.total !== null ? `${job.processed} / ${job.total} comptes` : "Préparation…"}
          </p>
        </>
      )}
      {job.status === "FAILED" && job.error && (
        <p role="alert" className="text-sm text-red-700">
          {job.error}
        </p>
      )}
      {job.status === "SUCCEEDED" && <JobReport job={job} />}
    </div>
  );
}
