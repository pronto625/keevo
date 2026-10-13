"use client";

import type { TenantListItem, TenantPage } from "@/types/tenant";
import { OWNER_LABEL, UNNAMED_TENANT_LABEL, planLabel, tenantStatusLabel } from "@/lib/labels";

interface TenantTableProps {
  data: TenantPage | undefined;
  isLoading: boolean;
  page: number;
  onPageChange: (page: number) => void;
  /** Opens the tenant drawer (owned by the page: a single drawer for the table and the alerts). */
  onSelect: (tenantId: string) => void;
}

/** « Suppression dans N jours » (singular at 1); a date already past → « Suppression imminente ». */
export function deletionCountdown(iso: string, now: number = Date.now()): string {
  const ms = new Date(iso).getTime() - now;
  if (!(ms > 0)) return "Suppression imminente";
  const days = Math.ceil(ms / (1000 * 60 * 60 * 24));
  return `Suppression dans ${days} ${days > 1 ? "jours" : "jour"}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("fr-FR");
}

const PLAN_BADGE: Record<string, string> = {
  FREE: "bg-gray-100 text-gray-700",
  PREMIUM_TRIAL: "bg-blue-100 text-blue-800",
  PREMIUM: "bg-purple-100 text-purple-800",
};

const STATUS_BADGE: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-800",
  DELETION_PENDING: "bg-orange-100 text-orange-800",
  SUSPENDED: "bg-red-100 text-red-800",
};

export function TenantTable({
  data,
  isLoading,
  page,
  onPageChange,
  onSelect,
}: TenantTableProps) {
  const totalCount = data?.totalCount ?? 0;
  const pageSize = data?.pageSize ?? 25;
  const start = page * pageSize + 1;
  const end = Math.min(start + pageSize - 1, totalCount);

  return (
    <div>
      <p className="text-sm text-gray-500 mb-2">
        {totalCount > 0
          ? `Affichage ${start}–${end} sur ${totalCount} ${totalCount > 1 ? "entreprises" : "entreprise"}`
          : "Aucune entreprise trouvée"}
      </p>

      <div className="overflow-x-auto rounded border border-gray-200">
        <table className="min-w-full text-sm divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {[
                "Entreprise",
                "Propriétaire",
                "Téléphone",
                "Plan",
                "Statut",
                "Inscription",
                "Dernière activité",
                "Boutiques",
                "Employés",
              ].map((h) => (
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
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-gray-400">
                  Chargement…
                </td>
              </tr>
            ) : !data?.items?.length ? (
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-gray-400">
                  Aucune entreprise
                </td>
              </tr>
            ) : (
              data.items.map((item: TenantListItem) => (
                <tr
                  key={item.id}
                  onClick={() => onSelect(item.id)}
                  data-deletion-pending={item.status === "DELETION_PENDING" ? "true" : undefined}
                  className={`cursor-pointer ${
                    item.status === "DELETION_PENDING"
                      ? "bg-orange-50 border-l-4 border-orange-400 hover:bg-orange-100"
                      : "hover:bg-gray-50"
                  }`}
                >
                  <td className="px-4 py-3 font-medium">
                    {item.name || UNNAMED_TENANT_LABEL}
                    {item.status === "DELETION_PENDING" &&
                      item.deletionScheduledAt && (
                        <span className="ml-2 text-xs font-medium text-orange-700">
                          {deletionCountdown(item.deletionScheduledAt)}
                        </span>
                      )}
                  </td>
                  <td className="px-4 py-3">{item.ownerName || OWNER_LABEL}</td>
                  <td className="px-4 py-3">{item.ownerPhone || "—"}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${PLAN_BADGE[item.plan] ?? "bg-gray-100 text-gray-700"}`}>
                      {planLabel(item.plan)}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_BADGE[item.status] ?? "bg-gray-100 text-gray-700"}`}>
                      {tenantStatusLabel(item.status)}
                    </span>
                  </td>
                  <td className="px-4 py-3">{formatDate(item.registeredAt)}</td>
                  <td className="px-4 py-3">{formatDate(item.lastActivityAt)}</td>
                  <td className="px-4 py-3 text-center">{item.storeCount}</td>
                  <td className="px-4 py-3 text-center">{item.employeeCount}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalCount > pageSize && (
        <div className="flex justify-end gap-2 mt-3">
          <button
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0}
            className="px-3 py-1 text-sm border rounded disabled:opacity-40"
          >
            ← Précédent
          </button>
          <button
            onClick={() => onPageChange(page + 1)}
            disabled={end >= totalCount}
            className="px-3 py-1 text-sm border rounded disabled:opacity-40"
          >
            Suivant →
          </button>
        </div>
      )}

    </div>
  );
}
