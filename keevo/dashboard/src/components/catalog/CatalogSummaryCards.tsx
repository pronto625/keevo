"use client";

import type { AdminCatalogSummary } from "@/types/catalog";

interface CatalogSummaryCardsProps {
  data: AdminCatalogSummary | undefined;
  isLoading: boolean;
}

interface CardProps {
  label: string;
  value: number | undefined;
  colorClass: string;
}

function SummaryCard({ label, value, colorClass }: CardProps) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <p className="text-sm text-gray-500">{label}</p>
      <p className={`text-3xl font-bold mt-1 ${colorClass}`}>
        {value ?? "—"}
      </p>
    </div>
  );
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-lg shadow p-4 animate-pulse">
      <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
      <div className="h-8 bg-gray-200 rounded w-16" />
    </div>
  );
}

export function CatalogSummaryCards({ data, isLoading }: CatalogSummaryCardsProps) {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        {Array.from({ length: 5 }).map((_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
      <SummaryCard
        label="Total Produits"
        value={data?.totalProducts}
        colorClass="text-gray-800"
      />
      <SummaryCard
        label="Actifs"
        value={data?.activeProducts}
        colorClass="text-green-600"
      />
      <SummaryCard
        label="Brouillons"
        value={data?.draftProducts}
        colorClass="text-blue-600"
      />
      <SummaryCard
        label="Stock Faible"
        value={data?.lowStockProducts}
        colorClass="text-amber-600"
      />
      <SummaryCard
        label="En Rupture"
        value={data?.outOfStockProducts}
        colorClass="text-red-600"
      />
    </div>
  );
}
