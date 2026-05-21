"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { CatalogSummaryCards } from "@/components/catalog/CatalogSummaryCards";
import { ProductFiltersPanel } from "@/components/catalog/ProductFilters";
import { ProductFilterChips } from "@/components/catalog/ProductFilterChips";
import { ProductTable } from "@/components/catalog/ProductTable";
import { useProducts } from "@/hooks/useProducts";
import { buildExportUrl } from "@/lib/api/admin-catalog";

export default function ProductsPage() {
  const {
    productsQuery,
    summaryQuery,
    filters,
    updateFilter,
    clearFilter,
    page,
    setPage,
  } = useProducts();

  return (
    <AdminLayout>
      <div className="p-6 space-y-5">
        <header className="flex items-center justify-between">
          <h1 className="text-xl font-bold text-gray-800">
            Catalogue Produits
          </h1>
          <a
            href={buildExportUrl(filters)}
            download
            className="px-3 py-1.5 text-sm bg-gray-800 text-white rounded hover:bg-gray-700 transition-colors"
          >
            ↓ Exporter CSV
          </a>
        </header>

        <CatalogSummaryCards
          data={summaryQuery.data}
          isLoading={summaryQuery.isLoading}
        />

        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <ProductFiltersPanel filters={filters} onUpdate={updateFilter} />
        </div>

        <ProductFilterChips filters={filters} clearFilter={clearFilter} />

        <ProductTable
          data={productsQuery.data}
          isLoading={productsQuery.isLoading}
          page={page}
          onPageChange={setPage}
        />
      </div>
    </AdminLayout>
  );
}
