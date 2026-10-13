"use client";

import { useMutation } from "@tanstack/react-query";
import { downloadProductsCsv } from "@/lib/api/admin-catalog";
import type { ProductFilters } from "@/types/catalog";

/** « Exporter CSV » of the catalog page — authenticated blob download (fix routed from 9-6). */
export function CatalogExportButton({ filters }: { filters: ProductFilters }) {
  const exportCsv = useMutation({ mutationFn: () => downloadProductsCsv(filters) });

  return (
    <div className="flex items-center gap-3">
      {exportCsv.isError && (
        <span role="alert" className="text-sm text-red-600">
          Échec de l&apos;export, réessayez
        </span>
      )}
      <button
        type="button"
        onClick={() => exportCsv.mutate()}
        disabled={exportCsv.isPending}
        className="px-3 py-1.5 text-sm bg-gray-800 text-white rounded hover:bg-gray-700 transition-colors disabled:opacity-50"
      >
        {exportCsv.isPending ? "Export en cours…" : "↓ Exporter CSV"}
      </button>
    </div>
  );
}
