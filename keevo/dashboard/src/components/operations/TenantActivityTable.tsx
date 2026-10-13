"use client";

import type { AdminTenantActivityPage } from "@/types/operations";
import { formatXAFFull, relativeTime } from "@/lib/format";
import { planLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { PeriodRequired } from "@/components/operations/PeriodRequired";

interface TenantActivityTableProps {
  data: AdminTenantActivityPage | undefined;
  isLoading: boolean;
  page: number;
  setPage: (page: number) => void;
  /** The last fetch failed with nothing to show. */
  isError?: boolean;
  onRetry?: () => void;
  /** The CUSTOM range is incomplete/invalid: no query runs. */
  rangeIncomplete?: boolean;
}

const COLUMNS = ["Entreprise", "Forfait", "Volume d'affaires", "Ventes", "Boutiques", "Dernière activité"];

export function TenantActivityTable({
  data,
  isLoading,
  page,
  setPage,
  isError = false,
  onRetry,
  rangeIncomplete = false,
}: TenantActivityTableProps) {
  const rows = data?.content ?? [];
  const totalPages = rangeIncomplete ? 0 : data?.totalPages ?? 0;

  return (
    <section className="bg-white rounded-lg shadow p-4">
      <h2 className="text-sm font-semibold text-gray-600 mb-3">
        Activité par entreprise
        {data && !rangeIncomplete && (
          <span className="font-normal text-gray-500"> ({data.totalElements})</span>
        )}
      </h2>

      {!rangeIncomplete && data && isError && (
        <div className="mb-3">
          <StaleDataWarning onRetry={onRetry} />
        </div>
      )}

      {rangeIncomplete ? (
        <PeriodRequired />
      ) : !data && !isLoading && isError ? (
        <LoadError onRetry={onRetry} />
      ) : (
        <div className="overflow-x-auto rounded border border-gray-200">
          <table className="min-w-full text-sm divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {COLUMNS.map((h) => (
                  <th
                    key={h}
                    className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="animate-pulse">
                    {COLUMNS.map((col) => (
                      <td key={col} className="px-4 py-3">
                        <div className="h-4 bg-gray-200 rounded w-full" />
                      </td>
                    ))}
                  </tr>
                ))
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={COLUMNS.length} className="px-4 py-8 text-center text-gray-400">
                    Aucune entreprise
                  </td>
                </tr>
              ) : (
                rows.map((row) => (
                  <tr key={row.tenantId} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium text-gray-900">
                      {row.tenantName?.trim() || "Sans nom"}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{planLabel(row.plan)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatXAFFull(row.gmv)}</td>
                    <td className="px-4 py-3 text-gray-700">{row.orderCount}</td>
                    <td className="px-4 py-3 text-gray-700">{row.storeCount}</td>
                    <td className="px-4 py-3 text-gray-500">
                      {row.lastActivityAt ? relativeTime(row.lastActivityAt) : "Jamais"}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-between mt-3">
          <span className="text-sm text-gray-500">
            Page {page + 1} / {totalPages}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setPage(page - 1)}
              disabled={page === 0}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40 hover:bg-gray-50"
            >
              ← Précédent
            </button>
            <button
              type="button"
              onClick={() => setPage(page + 1)}
              disabled={page + 1 >= totalPages}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40 hover:bg-gray-50"
            >
              Suivant →
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
