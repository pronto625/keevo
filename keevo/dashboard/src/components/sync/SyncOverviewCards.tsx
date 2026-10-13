"use client";

import type { AdminSyncOverview } from "@/types/sync-monitoring";
import { relativeTime } from "@/lib/format";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface SyncOverviewCardsProps {
  data: AdminSyncOverview | undefined;
  isLoading: boolean;
  onRefresh: () => void;
  /** The last fetch failed (first load or refresh). */
  isError?: boolean;
  /** A fetch is in flight (disables « Actualiser »). */
  isFetching?: boolean;
  /** Retries the failed query; defaults to onRefresh. */
  onRetry?: () => void;
}

interface CardProps {
  label: string;
  value: React.ReactNode;
  colorClass: string;
  title?: string;
}

function KpiCard({ label, value, colorClass, title }: CardProps) {
  return (
    <div className="bg-white rounded-lg shadow p-4" title={title}>
      <p className="text-sm text-gray-500">{label}</p>
      <p className={`text-3xl font-bold mt-1 ${colorClass}`}>{value ?? "—"}</p>
    </div>
  );
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-lg shadow p-4 animate-pulse">
      <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
      <div className="h-8 bg-gray-200 rounded w-16" />
    </div>
  );
}

export function SyncOverviewCards({
  data,
  isLoading,
  onRefresh,
  isError = false,
  isFetching = false,
  onRetry,
}: SyncOverviewCardsProps) {
  const retry = onRetry ?? onRefresh;
  // Pending without error (e.g. a query paused while offline) keeps the skeleton, not an error.
  const showSkeleton = isLoading || (!data && !isError);
  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-600">
          Vue d&apos;ensemble
        </h2>
        <button
          type="button"
          onClick={onRefresh}
          disabled={isFetching}
          className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          {isFetching ? "Actualisation…" : "Actualiser"}
        </button>
      </div>

      {data && isError && <StaleDataWarning onRetry={retry} />}

      {showSkeleton ? (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          {Array.from({ length: 5 }).map((_, i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : !data ? (
        <LoadError onRetry={retry} />
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          <KpiCard label="Appareils" value={data.totalDevices} colorClass="text-gray-800" />
          <KpiCard
            label="Opérations échouées (7j)"
            value={data.totalFailedOps7d}
            colorClass={data.totalFailedOps7d > 0 ? "text-amber-600" : "text-gray-800"}
          />
          <KpiCard
            label="Conflits (7j)"
            value={data.totalConflicts7d}
            colorClass={data.totalConflicts7d > 0 ? "text-amber-600" : "text-gray-800"}
          />
          <KpiCard
            label="Entreprises en alerte"
            value={data.tenantsInAlert}
            colorClass={data.tenantsInAlert > 0 ? "text-red-600" : "text-green-600"}
          />
          <KpiCard
            label="Dernier push"
            value={<span className="text-xl">{relativeTime(data.latestPushGlobally)}</span>}
            colorClass="text-gray-800"
            title={data.latestPushGlobally ?? undefined}
          />
        </div>
      )}
    </section>
  );
}
