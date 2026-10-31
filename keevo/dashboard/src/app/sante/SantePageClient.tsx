"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { HealthStatusCards } from "@/components/health/HealthStatusCards";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { useSystemHealth } from "@/hooks/useHealth";

/** Story 9-5 — « Santé système » (FR83): live metrics with thresholds. */
export default function SantePageClient() {
  const { query, refresh } = useSystemHealth();

  return (
    <AdminLayout>
      <div className="space-y-4">
        <header className="flex items-center justify-between">
          <h1 className="text-xl font-bold text-gray-800">Santé système</h1>
          <button
            type="button"
            onClick={refresh}
            disabled={query.isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Actualiser
          </button>
        </header>

        {query.isError && !query.data ? (
          <LoadError onRetry={() => void query.refetch()} />
        ) : (
          <>
            {query.isError && <StaleDataWarning onRetry={() => void query.refetch()} />}
            <HealthStatusCards data={query.data} isLoading={query.isLoading} />
          </>
        )}
      </div>
    </AdminLayout>
  );
}
