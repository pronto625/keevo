"use client";

import { useQuery } from "@tanstack/react-query";
import { getTenants } from "@/lib/api/admin-tenants";
import type { ProductFilters } from "@/types/catalog";

interface ProductFiltersProps {
  filters: ProductFilters;
  onUpdate: <K extends keyof ProductFilters>(key: K, value: ProductFilters[K]) => void;
}

export function ProductFiltersPanel({ filters, onUpdate }: ProductFiltersProps) {
  const tenantsQuery = useQuery({
    queryKey: ["tenants-for-filter"],
    queryFn: () =>
      getTenants(
        {
          search: "",
          plan: "ALL",
          status: "ALL",
          registeredFrom: null,
          registeredTo: null,
          lastActivityFrom: null,
          lastActivityTo: null,
        },
        0
      ),
    staleTime: 5 * 60_000,
  });

  return (
    <div className="flex flex-wrap gap-2 items-center">
      {/* Search */}
      <div className="relative">
        <span className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400 text-sm">
          🔍
        </span>
        <input
          type="text"
          placeholder="Rechercher un produit…"
          value={filters.search}
          onChange={(e) => onUpdate("search", e.target.value)}
          className="pl-7 pr-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 w-52"
        />
      </div>

      {/* Tenant */}
      <select
        value={filters.tenantId}
        onChange={(e) => onUpdate("tenantId", e.target.value)}
        className="text-sm border border-gray-300 rounded-md px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="">Tous les tenants</option>
        {tenantsQuery.data?.items.map((t) => (
          <option key={t.id} value={t.id}>
            {t.name}
          </option>
        ))}
      </select>

      {/* Stock Level */}
      <select
        value={filters.stockLevel}
        onChange={(e) =>
          onUpdate("stockLevel", e.target.value as ProductFilters["stockLevel"])
        }
        className="text-sm border border-gray-300 rounded-md px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="ALL">Stock (tous)</option>
        <option value="OK">OK (&gt;5)</option>
        <option value="LOW">Faible (1–5)</option>
        <option value="OUT">Rupture (0)</option>
      </select>

      {/* Status */}
      <select
        value={filters.status}
        onChange={(e) =>
          onUpdate("status", e.target.value as ProductFilters["status"])
        }
        className="text-sm border border-gray-300 rounded-md px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="ALL">Statut (tous)</option>
        <option value="ACTIVE">Actif</option>
        <option value="DRAFT">Brouillon</option>
        <option value="ARCHIVED">Archivé</option>
      </select>

      {/* Plan */}
      <select
        value={filters.plan}
        onChange={(e) =>
          onUpdate("plan", e.target.value as ProductFilters["plan"])
        }
        className="text-sm border border-gray-300 rounded-md px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500"
      >
        <option value="ALL">Plan (tous)</option>
        <option value="FREE">FREE</option>
        <option value="PAID">PAID</option>
      </select>
    </div>
  );
}
