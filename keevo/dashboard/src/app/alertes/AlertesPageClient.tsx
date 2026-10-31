"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { AlertList } from "@/components/alerts/AlertList";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { useAlerts } from "@/hooks/useAlerts";

/** Story 9-5 — « Alertes »: platform alerts feed, newest first. */
export default function AlertesPageClient() {
  const { query, includeHandled, setIncludeHandled, handle, pendingKeys, actionFailed, refresh } = useAlerts();

  return (
    <AdminLayout>
      <div className="space-y-4">
        <header className="flex items-center justify-between gap-4 flex-wrap">
          <h1 className="text-xl font-bold text-gray-800">Alertes</h1>
          <div className="flex items-center gap-4">
            <label className="flex items-center gap-2 text-sm text-gray-700">
              <input
                type="checkbox"
                checked={includeHandled}
                onChange={(e) => setIncludeHandled(e.target.checked)}
              />
              Afficher les alertes traitées
            </label>
            <button
              type="button"
              onClick={() => void refresh()}
              disabled={query.isFetching}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              Actualiser
            </button>
          </div>
        </header>

        {actionFailed && (
          <p role="alert" className="text-sm text-red-600">
            L&apos;action n&apos;a pas pu être enregistrée, réessayez.
          </p>
        )}

        {query.isError && !query.data ? (
          <LoadError onRetry={() => void query.refetch()} />
        ) : (
          <>
            {query.isError && <StaleDataWarning onRetry={() => void query.refetch()} />}
            <AlertList
              alerts={query.data}
              isLoading={query.isLoading}
              onHandle={(key) => handle.mutate(key)}
              pendingKeys={pendingKeys}
              includeHandled={includeHandled}
            />
          </>
        )}
      </div>
    </AdminLayout>
  );
}
