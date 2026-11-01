"use client";

import { useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { getModuleCatalogue, getModuleHistory, getTenantModules } from "@/lib/api/admin-modules";
import { getBundles } from "@/lib/api/admin-bundles";
import { getJob, getJobs, isJobRunning } from "@/lib/api/admin-jobs";
import { getPlanProfiles } from "@/lib/api/admin-plans";
import type { JobKind } from "@/types/modules";

/** Story 16.4 — query keys of the « Modules » console. */
export const moduleKeys = {
  catalogue: ["module-catalogue"] as const,
  tenant: (tenantId: string) => ["tenant-modules", tenantId] as const,
  history: (tenantId: string) => ["module-history", tenantId] as const,
  bundles: ["bundles"] as const,
  profiles: ["plan-profiles"] as const,
  job: (jobId: string | null) => ["admin-job", jobId] as const,
  jobs: (kind?: JobKind) => ["admin-jobs", kind ?? "ALL"] as const,
};

/** How often a running job is polled. */
export const JOB_POLL_MS = 1000;

export function useModuleCatalogue() {
  return useQuery({ queryKey: moduleKeys.catalogue, queryFn: getModuleCatalogue, staleTime: 60_000 });
}

export function useTenantModules(tenantId: string) {
  return useQuery({ queryKey: moduleKeys.tenant(tenantId), queryFn: () => getTenantModules(tenantId) });
}

export function useModuleHistory(tenantId: string) {
  return useQuery({ queryKey: moduleKeys.history(tenantId), queryFn: () => getModuleHistory(tenantId) });
}

export function useBundles() {
  return useQuery({ queryKey: moduleKeys.bundles, queryFn: getBundles });
}

export function usePlanProfiles() {
  return useQuery({ queryKey: moduleKeys.profiles, queryFn: getPlanProfiles });
}

/** Consecutive failed reads after which a job is no longer polled. */
export const JOB_MAX_ERRORS = 3;

/**
 * A job, polled every second while it is queued or running. Polling stops after {@link JOB_MAX_ERRORS} consecutive
 * failed reads (`stopped`: the caller shows a French error instead of polling for ever).
 */
export function useJob(jobId: string | null) {
  const failures = useRef(0);
  const query = useQuery({
    queryKey: moduleKeys.job(jobId),
    queryFn: async () => {
      try {
        const job = await getJob(jobId as string);
        failures.current = 0;
        return job;
      } catch (err) {
        failures.current += 1;
        throw err;
      }
    },
    enabled: !!jobId,
    retry: false,
    refetchInterval: (q) => {
      if (failures.current >= JOB_MAX_ERRORS) return false;
      return !q.state.data || isJobRunning(q.state.data) ? JOB_POLL_MS : false;
    },
  });
  // never spread the query result: reading its `promise` property creates a thenable whose rejection goes unhandled
  return {
    data: query.data,
    isError: query.isError,
    // eslint-disable-next-line react-hooks/refs -- read during render on purpose: the counter drives the message
    stopped: query.isError && failures.current >= JOB_MAX_ERRORS,
  };
}

/** The latest jobs of a kind (to show the last report when the page opens). */
export function useLatestJobs(kind: JobKind) {
  return useQuery({ queryKey: moduleKeys.jobs(kind), queryFn: () => getJobs(kind) });
}
