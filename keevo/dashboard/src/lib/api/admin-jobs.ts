// Story 16.4 — super-admin background jobs (progress and reports).
import client from "./client";
import type { AdminJob, JobKind } from "@/types/modules";

export async function getJob(jobId: string): Promise<AdminJob> {
  const res = await client.get<{ data: AdminJob }>(`/admin/jobs/${jobId}`);
  return res.data.data;
}

/** The 20 latest jobs, newest first (optionally of one kind). */
export async function getJobs(kind?: JobKind): Promise<AdminJob[]> {
  const res = await client.get<{ data: AdminJob[] }>("/admin/jobs", { params: kind ? { kind } : {} });
  return res.data.data;
}

/** Whether a job is still going (the console keeps polling it). */
export function isJobRunning(job: Pick<AdminJob, "status"> | null | undefined): boolean {
  return job?.status === "QUEUED" || job?.status === "RUNNING";
}
