"use client";

import type { InactiveTenant } from "@/types/operations";
import { planLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface InactiveTenantsSectionProps {
  data: InactiveTenant[] | undefined;
  isLoading?: boolean;
  /** The last fetch failed with nothing to show. */
  isError?: boolean;
  onRetry?: () => void;
}

/** Calendar date in Africa/Douala (the shops' time zone), whatever the browser's time zone. */
function formatDoualaDate(iso: string): string {
  return new Date(iso).toLocaleDateString("fr-FR", { timeZone: "Africa/Douala" });
}

function daysLabel(days: number | null): string {
  if (days === null) return "Jamais active";
  return days <= 1 ? `${days} jour` : `${days} jours`;
}

export function InactiveTenantsSection({
  data,
  isLoading = false,
  isError = false,
  onRetry,
}: InactiveTenantsSectionProps) {
  return (
    <section className="bg-white rounded-lg shadow p-4">
      <h2 className="text-sm font-semibold text-gray-600 mb-1">
        Entreprises inactives
        {data && <span className="font-normal text-gray-500"> ({data.length})</span>}
      </h2>
      <p className="text-xs text-gray-400 mb-3">
        Aucune vente ni action depuis plus de 14 jours.
      </p>

      {data && isError && (
        <div className="mb-3">
          <StaleDataWarning onRetry={onRetry} />
        </div>
      )}

      {!data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !data || isLoading ? (
        <div data-testid="inactive-skeleton" className="space-y-2 animate-pulse">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-6 bg-gray-200 rounded" />
          ))}
        </div>
      ) : data.length === 0 ? (
        <p className="text-sm text-gray-400 py-4 text-center">Aucune entreprise inactive</p>
      ) : (
        <div className="overflow-x-auto rounded border border-gray-200">
          <table className="min-w-full text-sm divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {["Entreprise", "Forfait", "Dernière activité", "Inactive depuis"].map((h) => (
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
              {data.map((t) => (
                <tr key={t.tenantId}>
                  <td className="px-4 py-3 font-medium text-gray-900">
                    {t.tenantName?.trim() || "Sans nom"}
                  </td>
                  <td className="px-4 py-3 text-gray-600">{planLabel(t.plan)}</td>
                  <td className="px-4 py-3 text-gray-500">
                    {t.lastActivityAt ? formatDoualaDate(t.lastActivityAt) : "Jamais"}
                  </td>
                  <td
                    className={`px-4 py-3 ${
                      t.daysSinceLastActivity === null || t.daysSinceLastActivity > 30
                        ? "text-red-600"
                        : "text-amber-600"
                    }`}
                  >
                    {daysLabel(t.daysSinceLastActivity)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
