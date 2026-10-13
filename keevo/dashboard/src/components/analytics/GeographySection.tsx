"use client";

import type { CountryCount } from "@/types/analytics";
import { formatPercent } from "@/lib/format";
import { ANALYTICS_LABELS, countryLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface GeographySectionProps {
  data: CountryCount[] | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

/** Story 9-4 — entreprises per country (V1 proxy: owner phone calling code). */
export function GeographySection({ data, isLoading, isError = false, onRetry }: GeographySectionProps) {
  const total = data?.reduce((sum, c) => sum + c.tenantCount, 0) ?? 0;
  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-gray-600">{ANALYTICS_LABELS.geographyTitle}</h2>
        <p className="text-xs text-gray-400">{ANALYTICS_LABELS.geographyHint}</p>
      </div>
      {data && isError && <StaleDataWarning onRetry={onRetry} />}
      {isLoading ? (
        <div data-testid="geography-skeleton" className="h-32 bg-gray-100 rounded animate-pulse" />
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !data || data.length === 0 ? (
        <p className="text-sm text-gray-400">{ANALYTICS_LABELS.noData}</p>
      ) : (
        <ul className="space-y-2">
          {data.map((c) => {
            const share = total > 0 ? c.tenantCount / total : 0;
            return (
              <li key={c.country} className="text-sm">
                <div className="flex justify-between text-gray-700">
                  <span>{countryLabel(c.country)}</span>
                  <span>
                    {c.tenantCount} <span className="text-gray-400">({formatPercent(share)})</span>
                  </span>
                </div>
                <div className="h-2 bg-gray-100 rounded mt-1">
                  <div className="h-2 bg-blue-500 rounded" style={{ width: `${share * 100}%` }} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
