"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { TenantTable } from "@/components/tenants/TenantTable";
import { TenantSearchBar } from "@/components/tenants/TenantSearchBar";
import { TenantFiltersPanel } from "@/components/tenants/TenantFilters";
import { TenantFilterChips } from "@/components/tenants/TenantFilterChips";
import { useTenants } from "@/hooks/useTenants";

export default function TenantsPage() {
  const {
    data,
    isLoading,
    filters,
    updateFilter,
    clearFilter,
    page,
    setPage,
  } = useTenants();

  return (
    <AdminLayout>
      <div className="space-y-4">
        <h1 className="text-xl font-bold text-gray-800">Gestion des Tenants</h1>

        {/* Barre de recherche + filtres */}
        <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
          <TenantSearchBar
            value={filters.search}
            onChange={(v) => updateFilter("search", v)}
          />
          <TenantFiltersPanel filters={filters} onUpdate={updateFilter} />
        </div>

        <TenantFilterChips filters={filters} onClear={clearFilter} />

        <TenantTable
          data={data}
          isLoading={isLoading}
          page={page}
          onPageChange={setPage}
        />
      </div>
    </AdminLayout>
  );
}
