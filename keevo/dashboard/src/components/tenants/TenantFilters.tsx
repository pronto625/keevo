"use client";

import { TENANT_PLAN_FILTERS, planFilterLabel, tenantStatusLabel } from "@/lib/labels";
import type { TenantFilters } from "@/types/tenant";

interface TenantFiltersProps {
  filters: TenantFilters;
  onUpdate: <K extends keyof TenantFilters>(key: K, value: TenantFilters[K]) => void;
}

const selectClass =
  "border border-gray-300 rounded-lg bg-white text-gray-900 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500";
const inputClass =
  "border border-gray-300 rounded-lg bg-white text-gray-900 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500";
const labelClass = "text-xs font-medium text-gray-600 mb-1 block";

export function TenantFiltersPanel({ filters, onUpdate }: TenantFiltersProps) {
  return (
    <div className="flex flex-wrap gap-3 items-end">
      <div>
        <label className={labelClass} htmlFor="tenant-filter-plan">Plan</label>
        <select
          id="tenant-filter-plan"
          value={filters.plan}
          onChange={(e) => onUpdate("plan", e.target.value as TenantFilters["plan"])}
          className={selectClass}
        >
          {TENANT_PLAN_FILTERS.map((p) => (
            <option key={p} value={p}>{planFilterLabel(p)}</option>
          ))}
          {/* Legacy filter (old links): kept selectable only while active. */}
          {filters.plan === "PAID" && <option value="PAID">{planFilterLabel("PAID")}</option>}
        </select>
      </div>

      <div>
        <label className={labelClass} htmlFor="tenant-filter-status">Statut</label>
        <select
          id="tenant-filter-status"
          value={filters.status}
          onChange={(e) => onUpdate("status", e.target.value as TenantFilters["status"])}
          className={selectClass}
        >
          <option value="ALL">Tous</option>
          <option value="ACTIVE">{tenantStatusLabel("ACTIVE")}</option>
          <option value="DELETION_PENDING">{tenantStatusLabel("DELETION_PENDING")}</option>
          <option value="SUSPENDED">{tenantStatusLabel("SUSPENDED")}</option>
        </select>
      </div>

      <div>
        <label className={labelClass}>Inscrit depuis</label>
        <input
          type="date"
          value={filters.registeredFrom ?? ""}
          onChange={(e) => onUpdate("registeredFrom", e.target.value || null)}
          className={inputClass}
        />
      </div>

      <div>
        <label className={labelClass}>Inscrit jusqu&apos;au</label>
        <input
          type="date"
          value={filters.registeredTo ?? ""}
          onChange={(e) => onUpdate("registeredTo", e.target.value || null)}
          className={inputClass}
        />
      </div>

      <div>
        <label className={labelClass}>Activité depuis</label>
        <input
          type="date"
          value={filters.lastActivityFrom ?? ""}
          onChange={(e) => onUpdate("lastActivityFrom", e.target.value || null)}
          className={inputClass}
        />
      </div>

      <div>
        <label className={labelClass}>Activité jusqu&apos;au</label>
        <input
          type="date"
          value={filters.lastActivityTo ?? ""}
          onChange={(e) => onUpdate("lastActivityTo", e.target.value || null)}
          className={inputClass}
        />
      </div>
    </div>
  );
}
