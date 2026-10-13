"use client";

import { useMemo } from "react";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { ComposeForm } from "@/components/notifications/ComposeForm";
import { NotificationHistory } from "@/components/notifications/NotificationHistory";
import { SendStatusCard } from "@/components/notifications/SendStatusCard";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { useGlobalNotifications } from "@/hooks/useGlobalNotifications";
import { apiErrorMessage, apiFieldErrors } from "@/lib/api/admin-tenants";

/** Story 9-5 (2/2) — « Notifications »: compose and send a global push, then follow its delivery. */
export default function NotificationsPage() {
  const { query, send, cancel, pendingCancelIds, tracked, trackedNotification, now, dismissTracked, refresh } =
    useGlobalNotifications();
  // Stable identity per answer: the form hides a server field error once that field is edited.
  const fieldErrors = useMemo(() => (send.error ? apiFieldErrors(send.error) : undefined), [send.error]);

  return (
    <AdminLayout>
      <div className="space-y-4">
        <header className="flex items-center justify-between gap-4 flex-wrap">
          <h1 className="text-xl font-bold text-gray-800">Notifications</h1>
          <button
            type="button"
            onClick={() => void refresh()}
            disabled={query.isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Actualiser
          </button>
        </header>

        <ComposeForm
          onSubmit={(input) => send.mutateAsync(input)}
          isSubmitting={send.isPending}
          serverError={send.isError ? apiErrorMessage(send.error, "L'annonce n'a pas pu être envoyée, réessayez.") : null}
          serverFieldErrors={fieldErrors}
        />

        {tracked && (
          <SendStatusCard
            notification={trackedNotification}
            estimate={tracked.estimate}
            now={now}
            onDismiss={dismissTracked}
          />
        )}

        <h2 className="text-lg font-semibold text-gray-800 pt-2">Historique (90 jours)</h2>

        {cancel.isError && (
          <p role="alert" className="text-sm text-red-600">
            L&apos;annonce n&apos;a pas pu être annulée (elle est peut-être déjà en cours d&apos;envoi).
          </p>
        )}

        {query.isError && !query.data ? (
          <LoadError onRetry={() => void query.refetch()} />
        ) : (
          <>
            {query.isError && <StaleDataWarning onRetry={() => void query.refetch()} />}
            <NotificationHistory
              notifications={query.data}
              isLoading={query.isLoading}
              onCancel={(id) => cancel.mutate(id)}
              pendingCancelIds={pendingCancelIds}
            />
          </>
        )}
      </div>
    </AdminLayout>
  );
}
