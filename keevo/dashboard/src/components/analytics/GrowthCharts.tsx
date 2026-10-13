"use client";

import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { GrowthPoint } from "@/types/analytics";
import { formatXAF, formatXAFFull } from "@/lib/format";
import { ANALYTICS_LABELS, monthLabel } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface GrowthChartsProps {
  data: GrowthPoint[] | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

/** Tooltip: French month and every series of the hovered month. */
export function GrowthTooltip({ active, payload }: { active?: boolean; payload?: ReadonlyArray<{ payload?: unknown }> }) {
  const point = payload?.[0]?.payload as GrowthPoint | undefined;
  if (!active || !point) return null;
  return (
    <div className="bg-white border border-gray-200 rounded shadow px-3 py-2 text-sm">
      <p className="font-medium text-gray-800">{monthLabel(point.month)}</p>
      <p className="text-gray-700">{ANALYTICS_LABELS.newTenants} : {point.newTenants}</p>
      <p className="text-gray-700">{ANALYTICS_LABELS.cumulativeTenants} : {point.cumulativeTenants}</p>
      <p className="text-gray-700">{ANALYTICS_LABELS.salesCount} : {point.salesCount}</p>
      <p className="text-gray-700">{ANALYTICS_LABELS.gmv} : {formatXAFFull(point.gmv)}</p>
      <p className="text-gray-700">{ANALYTICS_LABELS.monthlyActiveUsers} : {point.mau}</p>
    </div>
  );
}

const shortMonth = (m: string) => monthLabel(m, true);

/** Story 9-4 — growth curves of the last 12 months: entreprises, then sales volume and active users. */
export function GrowthCharts({ data, isLoading, isError = false, onRetry }: GrowthChartsProps) {
  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <h2 className="text-sm font-semibold text-gray-600">Croissance (12 derniers mois)</h2>
      {data && isError && <StaleDataWarning onRetry={onRetry} />}
      {isLoading ? (
        <div data-testid="growth-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !data || data.length === 0 ? (
        <p className="h-64 flex items-center justify-center text-sm text-gray-400">{ANALYTICS_LABELS.noData}</p>
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          <div className="h-64" data-testid="growth-tenants">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" vertical={false} />
                <XAxis dataKey="month" tickFormatter={shortMonth} tick={{ fontSize: 12 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 12 }} width={40} />
                <Tooltip content={GrowthTooltip} cursor={{ fill: "#f3f4f6" }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="newTenants" name={ANALYTICS_LABELS.newTenants} fill="#93c5fd" radius={[4, 4, 0, 0]} />
                <Line
                  type="monotone"
                  dataKey="cumulativeTenants"
                  name={ANALYTICS_LABELS.cumulativeTenants}
                  stroke="#2563eb"
                  strokeWidth={2}
                  dot={false}
                />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <div className="h-64" data-testid="growth-sales">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" vertical={false} />
                <XAxis dataKey="month" tickFormatter={shortMonth} tick={{ fontSize: 12 }} />
                <YAxis yAxisId="gmv" tickFormatter={(v: number) => formatXAF(v)} tick={{ fontSize: 12 }} width={72} />
                <YAxis yAxisId="mau" orientation="right" allowDecimals={false} tick={{ fontSize: 12 }} width={40} />
                <Tooltip content={GrowthTooltip} cursor={{ fill: "#f3f4f6" }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar yAxisId="gmv" dataKey="gmv" name={ANALYTICS_LABELS.gmv} fill="#10b981" radius={[4, 4, 0, 0]} />
                <Line
                  yAxisId="mau"
                  type="monotone"
                  dataKey="mau"
                  name={ANALYTICS_LABELS.monthlyActiveUsers}
                  stroke="#f59e0b"
                  strokeWidth={2}
                  dot={false}
                />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}
    </section>
  );
}
