"use client";

import type { AdminAlert } from "@/types/alerts";
import { alertSeverityLabel } from "@/lib/labels";
import { formatDate, relativeTime } from "@/lib/format";

interface AlertListProps {
  alerts: AdminAlert[] | undefined;
  isLoading: boolean;
  onHandle: (key: string) => void;
  pendingKeys: ReadonlySet<string>;
  includeHandled: boolean;
}

const SEVERITY_CLASSES: Record<string, string> = {
  HIGH: "bg-red-100 text-red-700",
  MEDIUM: "bg-orange-100 text-orange-700",
  LOW: "bg-gray-100 text-gray-700",
};

export function SeverityBadge({ severity }: { severity: string }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 text-xs font-semibold rounded-full ${
        SEVERITY_CLASSES[severity] ?? "bg-gray-100 text-gray-700"
      }`}
    >
      {alertSeverityLabel(severity)}
    </span>
  );
}

/** Alerts feed (Story 9-5): severity badge, title, description, « Marquer comme traité ». */
export function AlertList({ alerts, isLoading, onHandle, pendingKeys, includeHandled }: AlertListProps) {
  if (isLoading && !alerts) {
    return (
      <div className="space-y-3">
        {Array.from({ length: 3 }, (_, i) => (
          <div key={i} className="bg-white rounded-lg shadow p-4 animate-pulse">
            <div className="h-4 bg-gray-200 rounded w-40 mb-2" />
            <div className="h-3 bg-gray-200 rounded w-72" />
          </div>
        ))}
      </div>
    );
  }

  if (!alerts || alerts.length === 0) {
    return (
      <div className="bg-white rounded-lg shadow p-6 text-center text-sm text-gray-500">
        {includeHandled ? "Aucune alerte." : "Aucune alerte en cours. 🎉"}
      </div>
    );
  }

  return (
    <ul className="space-y-3">
      {alerts.map((alert) => {
        const pending = pendingKeys.has(alert.key);
        return (
          <li
            key={alert.key}
            className={`bg-white rounded-lg shadow p-4 flex items-start justify-between gap-4 ${
              alert.handled ? "opacity-70" : ""
            }`}
          >
            <div className="space-y-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <SeverityBadge severity={alert.severity} />
                <h3 className="text-sm font-semibold text-gray-800">{alert.title}</h3>
                <span className="text-xs text-gray-400">
                  {alert.occurredAt ? `${relativeTime(alert.occurredAt)} · ${formatDate(alert.occurredAt)}` : ""}
                </span>
              </div>
              <p className="text-sm text-gray-700 break-words">{alert.description}</p>
              {alert.handled && (
                <p className="text-xs text-green-700">Traitée le {formatDate(alert.handledAt)}</p>
              )}
            </div>
            {!alert.handled && (
              <button
                type="button"
                onClick={() => onHandle(alert.key)}
                disabled={pending}
                className="shrink-0 px-3 py-1.5 text-sm border border-gray-300 rounded bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                Marquer comme traité
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
