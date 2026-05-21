"use client";

import type { ProductFilters } from "@/types/catalog";

interface ProductFilterChipsProps {
  filters: ProductFilters;
  clearFilter: (key: keyof ProductFilters) => void;
}

const STOCK_LABELS: Record<string, string> = {
  OK: "Stock: OK (>5)",
  LOW: "Stock: Faible (1–5)",
  OUT: "Stock: Rupture (0)",
};

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "Statut: Actif",
  DRAFT: "Statut: Brouillon",
  ARCHIVED: "Statut: Archivé",
};

export function ProductFilterChips({
  filters,
  clearFilter,
}: ProductFilterChipsProps) {
  const chips: { label: string; key: keyof ProductFilters }[] = [];

  if (filters.search)
    chips.push({ label: `"${filters.search}"`, key: "search" });
  if (filters.tenantId)
    chips.push({ label: "Tenant: sélectionné", key: "tenantId" });
  if (filters.stockLevel !== "ALL")
    chips.push({ label: STOCK_LABELS[filters.stockLevel] ?? filters.stockLevel, key: "stockLevel" });
  if (filters.status !== "ALL")
    chips.push({ label: STATUS_LABELS[filters.status] ?? filters.status, key: "status" });
  if (filters.plan !== "ALL")
    chips.push({ label: `Plan: ${filters.plan}`, key: "plan" });

  if (chips.length === 0) return null;

  const hasActiveFilters = chips.length > 0;

  return (
    <div className="flex flex-wrap gap-2 items-center">
      {chips.map((chip) => (
        <span
          key={chip.key}
          className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 text-blue-700 text-xs rounded-full border border-blue-200"
        >
          {chip.label}
          <button
            onClick={() => clearFilter(chip.key)}
            className="hover:text-blue-900 font-bold"
            aria-label={`Supprimer filtre ${chip.label}`}
          >
            ×
          </button>
        </span>
      ))}
      {hasActiveFilters && (
        <button
          onClick={() => {
            (["search", "tenantId", "stockLevel", "status", "plan"] as (keyof ProductFilters)[]).forEach(
              clearFilter
            );
          }}
          className="text-xs text-gray-500 hover:text-gray-700 underline"
        >
          Réinitialiser tout
        </button>
      )}
    </div>
  );
}
