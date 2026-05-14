"use client";

import { useState } from "react";
import type { TenantListItem, TenantPage } from "@/types/tenant";
import { TenantDetailDrawer } from "./TenantDetailDrawer";

interface TenantTableProps {
  data: TenantPage | undefined;
  isLoading: boolean;
  page: number;
  onPageChange: (page: number) => void;
}

function daysUntil(iso: string): number {
  const ms = new Date(iso).getTime() - Date.now();
  return Math.max(0, Math.ceil(ms / (1000 * 60 * 60 * 24)));
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("fr-FR");
}

const STATUS_BADGE: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-800",
  DELETION_PENDING: "bg-amber-100 text-amber-800",
  SUSPENDED: "bg-red-100 text-red-800",
};

export function TenantTable({
  data,
  isLoading,
  page,
  onPageChange,
}: TenantTableProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const totalCount = data?.totalCount ?? 0;
  const pageSize = data?.pageSize ?? 25;
  const start = page * pageSize + 1;
  const end = Math.min(start + pageSize - 1, totalCount);

  return (
    <div>
      <p className="text-sm text-gray-500 mb-2">
        {totalCount > 0
          ? `Affichage ${start}–${end} sur ${totalCount} tenants`
          : "Aucun tenant trouvé"}
      </p>

      <div className="overflow-x-auto rounded border border-gray-200">
        <table className="min-w-full text-sm divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {[
                "Code",
                "Nom",
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
                  Aucun résultat
                </td>
              </tr>
            ) : (
              data.items.map((item: TenantListItem) => (
                <tr
                  key={item.id}
                  onClick={() => setSelectedId(item.id)}
                  className={`cursor-pointer hover:bg-gray-50 ${
                    item.status === "DELETION_PENDING"
                      ? "bg-amber-50 border-l-2 border-amber-400"
                      : ""
                  }`}
                >
                  <td className="px-4 py-3 font-mono text-xs">{item.code}</td>
                  <td className="px-4 py-3 font-medium">
                    {item.name}
                    {item.status === "DELETION_PENDING" &&
                      item.deletionScheduledAt && (
                        <span className="ml-2 text-xs text-amber-600">
                          Suppression dans {daysUntil(item.deletionScheduledAt)} j
                        </span>
                      )}
                  </td>
                  <td className="px-4 py-3">{item.ownerPhone}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      item.plan === "PAID"
                        ? "bg-purple-100 text-purple-800"
                        : "bg-gray-100 text-gray-700"
                    }`}>
                      {item.plan}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_BADGE[item.status]}`}>
                      {item.status}
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

      {selectedId && (
        <TenantDetailDrawer
          tenantId={selectedId}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  );
}
