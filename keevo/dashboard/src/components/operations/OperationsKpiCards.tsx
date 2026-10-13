"use client";

import type { AdminOperationsOverview } from "@/types/operations";
import { formatXAF, formatXAFFull } from "@/lib/format";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { PeriodRequired } from "@/components/operations/PeriodRequired";

interface OperationsKpiCardsProps {
  overview: AdminOperationsOverview | undefined;
  isLoading: boolean;
  /** Time of the last successful load, null before the first one. */
  lastUpdated: Date | null;
  onRefresh: () => void;
  /** The last fetch failed (first load or refresh). */
  isError?: boolean;
  /** A fetch is in flight (disables « Actualiser »). */
  isFetching?: boolean;
  /** Retries the failed query; defaults to onRefresh. */
  onRetry?: () => void;
  /** The CUSTOM range is incomplete/invalid: no query runs. */
  rangeIncomplete?: boolean;
}

function KpiCard({ label, value, title }: { label: string; value: React.ReactNode; title?: string }) {
  return (
    <div className="bg-white rounded-lg shadow p-4" title={title}>
      <p className="text-sm text-gray-500">{label}</p>
      <p className="text-3xl font-bold mt-1 text-gray-800">{value}</p>
    </div>
  );
}

function SkeletonCard() {
  return (
    <div data-testid="kpi-skeleton" className="bg-white rounded-lg shadow p-4 animate-pulse">
      <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
      <div className="h-8 bg-gray-200 rounded w-16" />
    </div>
  );
}

function formatTime(d: Date): string {
  return d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
}

export function OperationsKpiCards({
  overview,
  isLoading,
  lastUpdated,
  onRefresh,
  isError = false,
  isFetching = false,
  onRetry,
  rangeIncomplete = false,
}: OperationsKpiCardsProps) {
  const retry = onRetry ?? onRefresh;
  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-gray-600">
          Vue d&apos;ensemble
          {overview && !rangeIncomplete && (
            <span className="font-normal text-gray-500"> — {overview.periodLabel}</span>
          )}
        </h2>
        <div className="flex items-center gap-3">
          {lastUpdated && (
            <span className="text-xs text-gray-400">Mis à jour à {formatTime(lastUpdated)}</span>
          )}
          <button
            type="button"
            onClick={onRefresh}
            disabled={isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isFetching ? "Actualisation…" : "Actualiser"}
          </button>
        </div>
      </div>

      {!rangeIncomplete && overview && isError && <StaleDataWarning onRetry={retry} />}

      {rangeIncomplete ? (
        <PeriodRequired />
      ) : isLoading ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : !overview && isError ? (
        <LoadError onRetry={retry} />
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <KpiCard
            label="Volume d'affaires (GMV)"
            value={overview ? formatXAF(overview.totalGmv) : "—"}
            title={overview ? formatXAFFull(overview.totalGmv) : undefined}
          />
          <KpiCard label="Ventes" value={overview ? overview.totalOrders : "—"} />
          <KpiCard label="Entreprises actives" value={overview ? overview.activeTenantsCount : "—"} />
          <KpiCard label="Nouvelles entreprises" value={overview ? overview.newTenantsCount : "—"} />
        </div>
      )}
    </section>
  );
}
