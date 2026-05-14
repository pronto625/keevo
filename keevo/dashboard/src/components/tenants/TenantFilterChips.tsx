"use client";

import type { TenantFilters } from "@/types/tenant";

interface TenantFilterChipsProps {
  filters: TenantFilters;
  onClear: (key: keyof TenantFilters) => void;
}

export function TenantFilterChips({ filters, onClear }: TenantFilterChipsProps) {
  const chips: { label: string; key: keyof TenantFilters }[] = [];

  if (filters.search) chips.push({ label: `"${filters.search}"`, key: "search" });
  if (filters.plan !== "ALL") chips.push({ label: `Plan: ${filters.plan}`, key: "plan" });
  if (filters.status !== "ALL") chips.push({ label: `Statut: ${filters.status}`, key: "status" });
  if (filters.registeredFrom) chips.push({ label: `Depuis: ${filters.registeredFrom}`, key: "registeredFrom" });
  if (filters.registeredTo) chips.push({ label: `Jusqu'au: ${filters.registeredTo}`, key: "registeredTo" });
  if (filters.lastActivityFrom) chips.push({ label: `Activité depuis: ${filters.lastActivityFrom}`, key: "lastActivityFrom" });
  if (filters.lastActivityTo) chips.push({ label: `Activité jusqu'au: ${filters.lastActivityTo}`, key: "lastActivityTo" });

  if (chips.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2">
      {chips.map((chip) => (
        <span
          key={chip.key}
          className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 text-blue-700 text-xs rounded-full border border-blue-200"
        >
          {chip.label}
          <button
            onClick={() => onClear(chip.key)}
            className="hover:text-blue-900 font-bold"
            aria-label={`Supprimer filtre ${chip.label}`}
          >
            ×
          </button>
        </span>
      ))}
    </div>
  );
}
