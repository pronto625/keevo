"use client";

import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { AdoptionReport, FeatureAdoption } from "@/types/analytics";
import { formatPercent } from "@/lib/format";
import { ANALYTICS_LABELS, featureLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface AdoptionChartProps {
  report: AdoptionReport | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

type Row = FeatureAdoption & { label: string; percent: number };

/** Tooltip: feature, share and number of active entreprises using it. */
export function AdoptionTooltip({ active, payload }: { active?: boolean; payload?: ReadonlyArray<{ payload?: unknown }> }) {
  const row = payload?.[0]?.payload as Row | undefined;
  if (!active || !row) return null;
  return (
    <div className="bg-white border border-gray-200 rounded shadow px-3 py-2 text-sm">
      <p className="font-medium text-gray-800">{row.label}</p>
      <p className="text-gray-700">
        {formatPercent(row.rate)} — {row.tenantCount} entreprise(s)
      </p>
    </div>
  );
}

/** Story 9-4 — share of the active entreprises (30 days) using each feature, horizontal bars. */
export function AdoptionChart({ report, isLoading, isError = false, onRetry }: AdoptionChartProps) {
  const rows: Row[] = (report?.features ?? []).map((f) => ({
    ...f,
    label: featureLabel(f.feature),
    percent: Math.round(f.rate * 1000) / 10,
  }));
  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-gray-600">{ANALYTICS_LABELS.adoptionTitle}</h2>
        {report && (
          <p className="text-xs text-gray-400" data-testid="adoption-base">
            {report.activeTenants} entreprise(s) active(s) sur les 30 derniers jours
          </p>
        )}
      </div>
      {report && isError && <StaleDataWarning onRetry={onRetry} />}
      {isLoading ? (
        <div data-testid="adoption-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !report && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !report || report.activeTenants === 0 ? (
        <p className="text-sm text-gray-400">Aucune entreprise active sur les 30 derniers jours</p>
      ) : (
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} layout="vertical" margin={{ top: 8, right: 48, bottom: 0, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" horizontal={false} />
              <XAxis type="number" domain={[0, 100]} tickFormatter={(v: number) => `${v} %`} tick={{ fontSize: 12 }} />
              <YAxis type="category" dataKey="label" width={170} tick={{ fontSize: 12 }} />
              <Tooltip content={AdoptionTooltip} cursor={{ fill: "#f3f4f6" }} />
              <Bar dataKey="percent" fill="#2563eb" radius={[0, 4, 4, 0]}>
                <LabelList
                  dataKey="percent"
                  position="right"
                  formatter={(v: unknown) => `${new Intl.NumberFormat("fr-FR").format(Number(v))} %`}
                  style={{ fontSize: 12, fill: "#374151" }}
                />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
