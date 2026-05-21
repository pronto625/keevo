"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { getCatalogSummary, getProducts } from "@/lib/api/admin-catalog";
import type { ProductFilters } from "@/types/catalog";

const DEFAULT_FILTERS: ProductFilters = {
  search: "",
  tenantId: "",
  stockLevel: "ALL",
  status: "ALL",
  plan: "ALL",
};

export function useProducts() {
  const [filters, setFilters] = useState<ProductFilters>(DEFAULT_FILTERS);
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(filters.search), 300);
    return () => clearTimeout(t);
  }, [filters.search]);

  const effective: ProductFilters = { ...filters, search: debouncedSearch };

  const productsQuery = useQuery({
    queryKey: ["admin-products", effective, page],
    queryFn: () => getProducts(effective, page),
    placeholderData: (prev) => prev,
  });

  const summaryQuery = useQuery({
    queryKey: ["admin-catalog-summary"],
    queryFn: getCatalogSummary,
    staleTime: 60_000,
  });

  const updateFilter = <K extends keyof ProductFilters>(
    key: K,
    value: ProductFilters[K]
  ) => {
    setPage(0);
    setFilters((f) => ({ ...f, [key]: value }));
  };

  const clearFilter = (key: keyof ProductFilters) => {
    setPage(0);
    setFilters((f) => {
      const reset = { ...f };
      if (key === "search") reset.search = "";
      else if (key === "stockLevel") reset.stockLevel = "ALL";
      else if (key === "status") reset.status = "ALL";
      else if (key === "plan") reset.plan = "ALL";
      else if (key === "tenantId") reset.tenantId = "";
      return reset;
    });
  };

  return { productsQuery, summaryQuery, filters, updateFilter, clearFilter, page, setPage };
}
