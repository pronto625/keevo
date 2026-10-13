"use client";

import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { RevenueTrendPoint } from "@/types/revenue";
import { formatXAF, formatXAFFull } from "@/lib/format";
import { monthLabel, REVENUE_LABELS } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface MrrTrendChartProps {
  data: RevenueTrendPoint[] | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

/** Tooltip: month, MRR, paying tenants, new conversions, churned. */
export function MrrTooltip({ active, payload }: { active?: boolean; payload?: ReadonlyArray<{ payload?: unknown }> }) {
  const point = payload?.[0]?.payload as RevenueTrendPoint | undefined;
  if (!active || !point) return null;
  return (
    <div className="bg-white border border-gray-200 rounded shadow px-3 py-2 text-sm">
      <p className="font-medium text-gray-800">{monthLabel(point.month)}</p>
      <p className="text-gray-700">{REVENUE_LABELS.chartMrr} : {formatXAFFull(point.mrr)}</p>
      <p className="text-gray-700">{REVENUE_LABELS.chartPaying} : {point.payingTenants}</p>
      <p className="text-gray-700">{REVENUE_LABELS.chartNew} : {point.newPayingTenants}</p>
      <p className="text-gray-700">{REVENUE_LABELS.chartChurned} : {point.churnedTenants}</p>
    </div>
  );
}

/** Story 9-3 — MRR of the last 12 months (bar chart). */
export function MrrTrendChart({ data, isLoading, isError = false, onRetry }: MrrTrendChartProps) {
  return (
    <section className="bg-white rounded-lg shadow p-4">
      <h2 className="text-sm font-semibold text-gray-600 mb-3">Évolution du MRR (12 derniers mois)</h2>
      {data && isError && (
        <div className="mb-3">
          <StaleDataWarning onRetry={onRetry} />
        </div>
      )}
      {isLoading ? (
        <div data-testid="mrr-chart-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !data || data.length === 0 ? (
        <p className="h-64 flex items-center justify-center text-sm text-gray-400">Aucune donnée</p>
      ) : (
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" vertical={false} />
              <XAxis dataKey="month" tickFormatter={(m: string) => monthLabel(m, true)} tick={{ fontSize: 12 }} />
              <YAxis tickFormatter={(v: number) => formatXAF(v)} tick={{ fontSize: 12 }} width={72} />
              <Tooltip content={MrrTooltip} cursor={{ fill: "#f3f4f6" }} />
              <Bar dataKey="mrr" name={REVENUE_LABELS.chartMrr} fill="#2563eb" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
