"use client";

import type { AdminProductListItem, AdminProductPage } from "@/types/catalog";
import { formatXAFFull, formatDate } from "@/lib/format";

interface ProductTableProps {
  data: AdminProductPage | undefined;
  isLoading: boolean;
  page: number;
  onPageChange: (page: number) => void;
}

const STATUS_BADGE: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-800",
  DRAFT: "bg-blue-100 text-blue-800",
  ARCHIVED: "bg-gray-100 text-gray-600",
};

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Actif",
  DRAFT: "Brouillon",
  ARCHIVED: "Archivé",
};

function stockColorClass(qty: number): string {
  if (qty === 0) return "text-red-600 font-bold";
  if (qty <= 5) return "text-amber-600";
  return "text-green-600";
}

const COLUMNS = [
  "Produit",
  "Tenant",
  "Plan",
  "Catégorie",
  "Prix",
  "Stock",
  "Statut",
  "Dernière màj",
];

export function ProductTable({
  data,
  isLoading,
  page,
  onPageChange,
}: ProductTableProps) {
  const totalCount = data?.totalCount ?? 0;
  const pageSize = data?.pageSize ?? 25;
  const start = page * pageSize + 1;
  const end = Math.min(start + pageSize - 1, totalCount);

  return (
    <div>
      <p className="text-sm text-gray-500 mb-2">
        {totalCount > 0
          ? `Affichage ${start}–${end} sur ${totalCount} produits`
          : "Aucun produit trouvé pour les filtres sélectionnés."}
      </p>

      <div className="overflow-x-auto rounded border border-gray-200">
        <table className="min-w-full text-sm divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {COLUMNS.map((h) => (
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
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i}>
                  {COLUMNS.map((col) => (
                    <td key={col} className="px-4 py-3">
                      <div className="h-4 bg-gray-200 rounded animate-pulse w-full" />
                    </td>
                  ))}
                </tr>
              ))
            ) : !data?.items?.length ? (
              <tr>
                <td
                  colSpan={COLUMNS.length}
                  className="px-4 py-8 text-center text-gray-400"
                >
                  Aucun produit trouvé pour les filtres sélectionnés.
                </td>
              </tr>
            ) : (
              data.items.map((item: AdminProductListItem) => (
                <tr key={item.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">
                    {item.name}
                  </td>
                  <td className="px-4 py-3 text-gray-600">{item.tenantName}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-medium ${
                        item.tenantPlan === "PAID"
                          ? "bg-yellow-100 text-yellow-800"
                          : "bg-gray-100 text-gray-600"
                      }`}
                    >
                      {item.tenantPlan}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-600">
                    {item.categoryName || "—"}
                  </td>
                  <td className="px-4 py-3 text-gray-700">
                    {formatXAFFull(item.price)}
                  </td>
                  <td className={`px-4 py-3 ${stockColorClass(item.stockQuantity)}`}>
                    {item.stockQuantity}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-medium ${
                        STATUS_BADGE[item.status] ?? "bg-gray-100 text-gray-600"
                      }`}
                    >
                      {STATUS_LABEL[item.status] ?? item.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {formatDate(item.updatedAt)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {totalCount > pageSize && (
        <div className="flex items-center justify-between mt-3">
          <span className="text-sm text-gray-500">
            Page {page + 1} / {Math.ceil(totalCount / pageSize)}
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40 hover:bg-gray-50"
            >
              ← Précédent
            </button>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={end >= totalCount}
              className="px-3 py-1 text-sm border rounded disabled:opacity-40 hover:bg-gray-50"
            >
              Suivant →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
