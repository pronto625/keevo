"use client";

import { globalFailureReasonLabel, globalNotificationStatusLabel, globalTargetLabel } from "@/lib/labels";
import type { GlobalNotification } from "@/types/global-notification";

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" });
}

const STATUS_TONES: Record<string, string> = {
  SCHEDULED: "bg-blue-100 text-blue-800",
  SENDING: "bg-amber-100 text-amber-800",
  SENT: "bg-green-100 text-green-800",
  FAILED: "bg-red-100 text-red-800",
  CANCELLED: "bg-gray-100 text-gray-700",
};

function count(n: number | null): string {
  return n == null ? "—" : String(n);
}

/** History of the global pushes of the last 90 days (newest first). */
export function NotificationHistory({
  notifications,
  isLoading,
  onCancel,
  pendingCancelIds,
}: {
  notifications: GlobalNotification[] | undefined;
  isLoading: boolean;
  onCancel: (id: string) => void;
  pendingCancelIds: ReadonlySet<string>;
}) {
  if (isLoading && !notifications) {
    return (
      <div className="bg-white rounded-lg shadow p-4 space-y-2" aria-busy="true">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-6 bg-gray-200 rounded animate-pulse" />
        ))}
      </div>
    );
  }

  if (!notifications || notifications.length === 0) {
    return (
      <div className="bg-white rounded-lg shadow p-4 text-sm text-gray-500">
        Aucune annonce sur les 90 derniers jours
      </div>
    );
  }

  return (
    <div className="bg-white rounded-lg shadow overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead className="bg-gray-50 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">
          <tr>
            <th className="px-4 py-2">Date</th>
            <th className="px-4 py-2">Titre</th>
            <th className="px-4 py-2">Cible</th>
            <th className="px-4 py-2 text-right">Appareils ciblés</th>
            <th className="px-4 py-2 text-right">Livrés</th>
            <th className="px-4 py-2 text-right">Échecs</th>
            <th className="px-4 py-2">Statut</th>
            <th className="px-4 py-2">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {notifications.map((n) => (
            <tr key={n.id}>
              <td className="px-4 py-2 whitespace-nowrap text-gray-700">{formatDateTime(n.sentAt ?? n.scheduledAt)}</td>
              <td className="px-4 py-2 text-gray-900">
                <span className="font-medium">{n.title}</span>
                <span className="block text-xs text-gray-500 truncate max-w-xs">{n.body}</span>
              </td>
              <td className="px-4 py-2 text-gray-700">{globalTargetLabel(n.target, n.targetTenantName)}</td>
              <td className="px-4 py-2 text-right tabular-nums">{count(n.recipientCount)}</td>
              <td className="px-4 py-2 text-right tabular-nums">{count(n.deliveredCount)}</td>
              <td className="px-4 py-2 text-right tabular-nums">{count(n.failureCount)}</td>
              <td className="px-4 py-2">
                <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_TONES[n.status] ?? "bg-gray-100 text-gray-700"}`}>
                  {globalNotificationStatusLabel(n.status)}
                </span>
                {n.status === "FAILED" && globalFailureReasonLabel(n.failureReason) && (
                  <span className="block mt-1 text-xs text-red-700">{globalFailureReasonLabel(n.failureReason)}</span>
                )}
              </td>
              <td className="px-4 py-2 text-right">
                {n.status === "SCHEDULED" && (
                  <button
                    type="button"
                    onClick={() => onCancel(n.id)}
                    disabled={pendingCancelIds.has(n.id)}
                    className="px-2 py-1 text-xs border border-gray-300 rounded text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                  >
                    Annuler
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
