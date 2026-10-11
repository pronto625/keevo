"use client";

import type { AdminTenantSyncHealth, SyncHealthStatus } from "@/types/sync-monitoring";
import { relativeTime } from "@/lib/format";

interface SyncHealthTableProps {
  data: AdminTenantSyncHealth[] | undefined;
  isLoading: boolean;
  onRowClick: (tenantId: string) => void;
  selectedTenantId: string | null;
}

const STATUS_STYLES: Record<SyncHealthStatus, string> = {
  OK: "bg-green-100 text-green-800",
  WARN: "bg-amber-100 text-amber-800",
  ERROR: "bg-red-100 text-red-800",
};

function SyncStatusBadge({ status }: { status: SyncHealthStatus }) {
  return (
    <span
      className={`px-2 py-0.5 rounded text-xs font-medium ${
        STATUS_STYLES[status] ?? "bg-gray-100 text-gray-700"
      }`}
    >
      {status}
    </span>
  );
}

const COLUMNS = [
  "Tenant",
  "Plan",
  "Statut",
  "Appareils",
  "Échecs 7j",
  "Conflits 7j",
  "Dernier push",
  "Dernier pull",
];

function SkeletonRow() {
  return (
    <tr className="animate-pulse">
      {COLUMNS.map((c) => (
        <td key={c} className="px-4 py-3">
          <div className="h-4 bg-gray-200 rounded w-16" />
        </td>
      ))}
    </tr>
  );
}

export function SyncHealthTable({
  data,
  isLoading,
  onRowClick,
  selectedTenantId,
}: SyncHealthTableProps) {
  return (
    <div className="bg-white rounded-lg shadow overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>
            {COLUMNS.map((c) => (
              <th
                key={c}
                className="text-left px-4 py-3 text-xs font-medium text-gray-500 uppercase"
              >
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {isLoading ? (
            Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} />)
          ) : !data ? (
            <tr>
              <td colSpan={COLUMNS.length} className="px-4 py-8 text-center text-red-600">
                Erreur de chargement de la santé de synchronisation
              </td>
            </tr>
          ) : data.length === 0 ? (
            <tr>
              <td colSpan={COLUMNS.length} className="px-4 py-8 text-center text-gray-400">
                Aucun tenant
              </td>
            </tr>
          ) : (
            data.map((t) => {
              const selected = t.tenantId === selectedTenantId;
              return (
                <tr
                  key={t.tenantId}
                  role="button"
                  tabIndex={0}
                  aria-pressed={selected}
                  onClick={() => onRowClick(t.tenantId)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      onRowClick(t.tenantId);
                    }
                  }}
                  className={`cursor-pointer focus:outline-none focus:ring-2 focus:ring-inset focus:ring-blue-400 ${
                    selected ? "bg-blue-50" : "hover:bg-gray-50"
                  }`}
                >
                  <td className="px-4 py-3 font-medium text-gray-900">{t.tenantName ?? "Sans nom"}</td>
                  <td className="px-4 py-3 text-gray-500">{t.tenantPlan ?? "—"}</td>
                  <td className="px-4 py-3">
                    <SyncStatusBadge status={t.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-900">{t.deviceCount}</td>
                  <td className="px-4 py-3 text-gray-900">{t.failedOps7d}</td>
                  <td className="px-4 py-3 text-gray-900">{t.conflicts7d}</td>
                  <td className="px-4 py-3 text-gray-600" title={t.lastPushAt ?? undefined}>
                    {relativeTime(t.lastPushAt)}
                  </td>
                  <td className="px-4 py-3 text-gray-600" title={t.lastPullAt ?? undefined}>
                    {relativeTime(t.lastPullAt)}
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
