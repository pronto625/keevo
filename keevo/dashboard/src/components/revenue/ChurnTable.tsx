"use client";

import type { ChurnReport } from "@/types/revenue";
import { formatIsoDay, formatPercent, formatXAFFull } from "@/lib/format";
import { monthLabel, REVENUE_LABELS } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface ChurnTableProps {
  report: ChurnReport | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
  /** Selected month, `YYYY-MM` ("" while the picker is cleared). */
  month: string;
  onMonthChange: (month: string) => void;
  monthValid: boolean;
  onExport: () => void;
  isExporting?: boolean;
  exportFailed?: boolean;
}

const COLUMNS = ["Entreprise", "Fin du plan", "Ancienneté payante", "CA des 30 jours avant la fin"];

function tenure(months: number | null): string {
  if (months === null) return "—";
  return `${new Intl.NumberFormat("fr-FR", { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(months)} mois`;
}

/** Story 9-3 — tenants lost in the selected month, with CSV export. */
export function ChurnTable({
  report,
  isLoading,
  isError = false,
  onRetry,
  month,
  onMonthChange,
  monthValid,
  onExport,
  isExporting = false,
  exportFailed = false,
}: ChurnTableProps) {
  const data = monthValid ? report : undefined;
  const rows = data?.tenants ?? [];

  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-gray-600">
          {REVENUE_LABELS.churnTitle} — {monthLabel(month)}
          {data && (
            <span className="font-normal text-gray-500">
              {" "}· {REVENUE_LABELS.churnRate} :{" "}
              {data.payingAtMonthStart > 0
                ? `${formatPercent(data.churnRate)} (${data.churnedCount} sur ${data.payingAtMonthStart})`
                : "—"}
            </span>
          )}
        </h2>
        <div className="flex items-center gap-3">
          <label className="text-sm text-gray-600 flex items-center gap-2">
            Mois
            <input
              type="month"
              value={month}
              onChange={(e) => onMonthChange(e.target.value)}
              className="border border-gray-300 rounded-lg px-2 py-1 text-gray-800"
            />
          </label>
          <button
            type="button"
            onClick={onExport}
            disabled={!monthValid || isExporting}
            className="px-3 py-1.5 text-sm bg-gray-800 text-white rounded hover:bg-gray-700 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isExporting ? "Export…" : `↓ ${REVENUE_LABELS.exportCsv}`}
          </button>
        </div>
      </div>

      {exportFailed && (
        <p role="alert" className="text-sm text-red-600">
          L&apos;export a échoué, réessayez.
        </p>
      )}

      {data && isError && <StaleDataWarning onRetry={onRetry} />}

      {!monthValid ? (
        <p className="text-sm text-gray-500 bg-gray-50 rounded-lg px-4 py-3">Choisissez un mois</p>
      ) : isLoading ? (
        <div data-testid="churn-skeleton" className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-8 bg-gray-100 rounded animate-pulse" />
          ))}
        </div>
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : rows.length === 0 ? (
        <p className="text-sm text-gray-400 py-6 text-center">{REVENUE_LABELS.churnEmpty}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b border-gray-200">
                {COLUMNS.map((c) => (
                  <th key={c} className="py-2 pr-4 font-medium">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={`${row.tenantName}-${row.planEndDate}-${i}`} className="border-b border-gray-100">
                  <td className="py-2 pr-4 text-gray-800">{row.tenantName}</td>
                  <td className="py-2 pr-4 text-gray-700">{formatIsoDay(row.planEndDate)}</td>
                  <td className="py-2 pr-4 text-gray-700">{tenure(row.tenureMonths)}</td>
                  <td className="py-2 pr-4 text-gray-700">{formatXAFFull(row.lastGmv30d)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
