"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { FeedbackFiltersPanel } from "@/components/feedback/FeedbackFilters";
import { FeedbackTable } from "@/components/feedback/FeedbackTable";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { useFeedback } from "@/hooks/useFeedback";

/** Spec 9-1 completion — « Retours utilisateurs » inbox (FR92, feedback sent from the app). */
export default function RetoursPage() {
  const { query, filters, updateFilter, page, setPage, handle, unhandle, pendingIds, actionFailed, refresh } =
    useFeedback();

  return (
    <AdminLayout>
      <div className="p-6 space-y-4">
        <header className="flex items-center justify-between">
          <h1 className="text-xl font-bold text-gray-800">Retours utilisateurs</h1>
          <button
            type="button"
            onClick={refresh}
            disabled={query.isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Actualiser
          </button>
        </header>

        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <FeedbackFiltersPanel filters={filters} onUpdate={updateFilter} />
        </div>

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
            <FeedbackTable
              data={query.data}
              isLoading={query.isLoading}
              page={page}
              onPageChange={setPage}
              onHandle={(id) => handle.mutate(id)}
              onUnhandle={(id) => unhandle.mutate(id)}
              pendingIds={pendingIds}
            />
          </>
        )}
      </div>
    </AdminLayout>
  );
}
