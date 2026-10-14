"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import {
  activatePremium,
  downgradeToFree,
  getPlanAlerts,
  getTenantPayments,
  getTenants,
  reactivateTenant,
  suspendTenant,
  voidPayment,
} from "@/lib/api/admin-tenants";
import type {
  ActivatePremiumInput,
  PlanAlert,
  TenantFilters,
  TenantPage,
  TenantPayment,
} from "@/types/tenant";

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

// ── Story 9-2 — plan & payments ───────────────────────────────────────────────

/** Plans expiring within 7 days / expired in the last 7 days (Tenants page banner). */
export function usePlanAlerts() {
  return useQuery<PlanAlert[]>({
    queryKey: ["plan-alerts"],
    queryFn: getPlanAlerts,
    staleTime: 60_000,
  });
}

/** Payment history of a tenant, newest first. */
export function useTenantPayments(tenantId: string) {
  return useQuery<TenantPayment[]>({
    queryKey: ["tenant-payments", tenantId],
    queryFn: () => getTenantPayments(tenantId),
  });
}

/**
 * Plan & suspension actions of the tenant drawer. Every success refreshes the tenant list, the
 * drawer detail, the payment history and the plan alerts.
 */
export function useTenantPlanActions(tenantId: string) {
  const queryClient = useQueryClient();
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["tenants"] });
    queryClient.invalidateQueries({ queryKey: ["tenant-detail", tenantId] });
    queryClient.invalidateQueries({ queryKey: ["tenant-payments", tenantId] });
    queryClient.invalidateQueries({ queryKey: ["plan-alerts"] });
  };

  const activate = useMutation({
    mutationFn: (input: ActivatePremiumInput) => activatePremium(tenantId, input),
    onSuccess: invalidate,
  });
  const downgrade = useMutation({
    mutationFn: (reason: string) => downgradeToFree(tenantId, reason),
    onSuccess: invalidate,
  });
  const suspend = useMutation({
    mutationFn: (reason: string) => suspendTenant(tenantId, reason),
    onSuccess: invalidate,
  });
  const reactivate = useMutation({
    mutationFn: () => reactivateTenant(tenantId),
    onSuccess: invalidate,
  });
  const voidPaymentMutation = useMutation({
    mutationFn: ({ paymentId, reason }: { paymentId: string; reason: string }) =>
      voidPayment(tenantId, paymentId, reason),
    onSuccess: invalidate,
  });

  return { activate, downgrade, suspend, reactivate, voidPayment: voidPaymentMutation };
}
