"use client";

import type { RetentionReport } from "@/types/analytics";
import { formatPercent } from "@/lib/format";
import { ANALYTICS_LABELS, cohortWeekLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface RetentionTableProps {
  report: RetentionReport | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

/** Cell colour: green > 60 %, yellow 30–60 %, red < 30 %; no colour for a future / empty cell. */
export function retentionCellClass(ratio: number | null): string {
  if (ratio === null) return "text-gray-400";
  if (ratio > 0.6) return "bg-green-100 text-green-800";
  if (ratio >= 0.3) return "bg-yellow-100 text-yellow-800";
  return "bg-red-100 text-red-800";
}

/** Story 9-4 — weekly cohorts of entreprises: share of each cohort active during week k after signup. */
export function RetentionTable({ report, isLoading, isError = false, onRetry }: RetentionTableProps) {
  const weeks = report?.weeks ?? 8;
  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-gray-600">{ANALYTICS_LABELS.retentionTitle}</h2>
        <p className="text-xs text-gray-400">
          Part des entreprises de la cohorte ayant au moins un utilisateur actif pendant la semaine
        </p>
      </div>
      {report && isError && <StaleDataWarning onRetry={onRetry} />}
      {isLoading ? (
        <div data-testid="retention-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !report && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !report || report.cohorts.length === 0 ? (
        <p className="text-sm text-gray-400">{ANALYTICS_LABELS.noData}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b">
                <th className="py-2 pr-4 font-medium">{ANALYTICS_LABELS.cohort}</th>
                <th className="py-2 pr-4 font-medium text-right">{ANALYTICS_LABELS.cohortSize}</th>
                {Array.from({ length: weeks }).map((_, k) => (
                  <th key={k} className="py-2 px-2 font-medium text-center">
                    Semaine {k + 1}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {report.cohorts.map((c) => (
                <tr key={c.weekStart} className="border-b last:border-0">
                  <td className="py-2 pr-4 text-gray-700 whitespace-nowrap">{cohortWeekLabel(c.weekStart)}</td>
                  <td className="py-2 pr-4 text-right text-gray-700">{c.tenantCount}</td>
                  {Array.from({ length: weeks }).map((_, k) => {
                    const ratio = c.retention[k] ?? null;
                    return (
                      <td key={k} className="py-1 px-1 text-center">
                        <span
                          data-testid="retention-cell"
                          className={`block rounded px-2 py-1 ${retentionCellClass(ratio)}`}
                        >
                          {ratio === null ? "—" : formatPercent(ratio)}
                        </span>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-xs text-gray-400 mt-2" data-testid="retention-legend">
            {ANALYTICS_LABELS.retentionLegend}
          </p>
        </div>
      )}
    </section>
  );
}
