"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { GmvTrendPoint } from "@/types/operations";
import { formatXAF, formatXAFFull } from "@/lib/format";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import { PeriodRequired } from "@/components/operations/PeriodRequired";

interface GmvTrendChartProps {
  data: GmvTrendPoint[] | undefined;
  isLoading: boolean;
  /** The last fetch failed with nothing to show. */
  isError?: boolean;
  onRetry?: () => void;
  /** The CUSTOM range is incomplete/invalid: no query runs. */
  rangeIncomplete?: boolean;
}

/** `2026-10-13` → `13/10` (string split: no time-zone shift). */
function shortDay(iso: string): string {
  const [, m, d] = iso.split("-");
  return d && m ? `${d}/${m}` : iso;
}

/** `2026-10-13` → `13/10/2026`. */
function fullDay(iso: string): string {
  const [y, m, d] = iso.split("-");
  return d && m && y ? `${d}/${m}/${y}` : iso;
}

/** Tooltip: day, GMV and number of sales of the hovered point. */
function TrendTooltip({ active, payload }: { active?: boolean; payload?: ReadonlyArray<{ payload?: unknown }> }) {
  const point = payload?.[0]?.payload as GmvTrendPoint | undefined;
  if (!active || !point) return null;
  return (
    <div className="bg-white border border-gray-200 rounded shadow px-3 py-2 text-sm">
      <p className="font-medium text-gray-800">{fullDay(point.date)}</p>
      <p className="text-gray-700">Volume d&apos;affaires : {formatXAFFull(point.gmv)}</p>
      <p className="text-gray-700">Ventes : {point.orderCount}</p>
    </div>
  );
}

export function GmvTrendChart({
  data,
  isLoading,
  isError = false,
  onRetry,
  rangeIncomplete = false,
}: GmvTrendChartProps) {
  return (
    <section className="bg-white rounded-lg shadow p-4">
      <h2 className="text-sm font-semibold text-gray-600 mb-3">Évolution du volume d&apos;affaires</h2>
      {!rangeIncomplete && data && isError && (
        <div className="mb-3">
          <StaleDataWarning onRetry={onRetry} />
        </div>
      )}
      {rangeIncomplete ? (
        <PeriodRequired />
      ) : isLoading ? (
        <div data-testid="trend-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : !data || data.length === 0 ? (
        <p className="h-64 flex items-center justify-center text-sm text-gray-400">Aucune donnée</p>
      ) : (
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis dataKey="date" tickFormatter={shortDay} tick={{ fontSize: 12 }} minTickGap={16} />
              <YAxis
                tickFormatter={(v: number) => formatXAF(v)}
                tick={{ fontSize: 12 }}
                width={72}
              />
              <Tooltip content={TrendTooltip} />
              <Area
                type="monotone"
                dataKey="gmv"
                stroke="#2563eb"
                fill="#bfdbfe"
                strokeWidth={2}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
