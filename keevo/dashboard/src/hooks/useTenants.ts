"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { getTenants } from "@/lib/api/admin-tenants";
import type { TenantFilters, TenantPage } from "@/types/tenant";

const DEFAULT_FILTERS: TenantFilters = {
  search: "",
  plan: "ALL",
  status: "ALL",
  registeredFrom: null,
  registeredTo: null,
  lastActivityFrom: null,
  lastActivityTo: null,
};

export function useTenants() {
  const [filters, setFilters] = useState<TenantFilters>(DEFAULT_FILTERS);
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);

  // 300ms debounce on search
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(filters.search), 300);
    return () => clearTimeout(timer);
  }, [filters.search]);

  const effectiveFilters = { ...filters, search: debouncedSearch };

  const query = useQuery<TenantPage>({
    queryKey: ["tenants", effectiveFilters, page],
    queryFn: () => getTenants(effectiveFilters, page),
    placeholderData: (prev) => prev,
  });

  const updateFilter = <K extends keyof TenantFilters>(
    key: K,
    value: TenantFilters[K]
  ) => {
    setPage(0);
    setFilters((f) => ({ ...f, [key]: value }));
  };

  const clearFilter = (key: keyof TenantFilters) => {
    const reset: TenantFilters = { ...filters };
    if (key === "search") reset.search = "";
    else if (key === "plan") reset.plan = "ALL";
    else if (key === "status") reset.status = "ALL";
    else (reset as unknown as Record<string, unknown>)[key] = null;
    setPage(0);
    setFilters(reset);
  };

  return { ...query, filters, updateFilter, clearFilter, page, setPage };
}
