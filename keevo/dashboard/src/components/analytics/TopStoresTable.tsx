"use client";

import { useMemo, useState } from "react";
import type { TopStore, TopStoresReport } from "@/types/analytics";
import { formatIsoDay, formatXAFFull } from "@/lib/format";
import { ANALYTICS_LABELS } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface TopStoresTableProps {
  report: TopStoresReport | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
  /** The range is invalid: no query runs. */
  rangeInvalid?: boolean;
}

export type TopStoreSortKey = keyof TopStore;
type SortState = { key: TopStoreSortKey; direction: "asc" | "desc" };

const COLUMNS: { key: TopStoreSortKey; label: string; numeric: boolean }[] = [
  { key: "storeName", label: ANALYTICS_LABELS.store, numeric: false },
  { key: "tenantName", label: ANALYTICS_LABELS.tenant, numeric: false },
  { key: "gmv", label: ANALYTICS_LABELS.gmv, numeric: true },
  { key: "transactions", label: ANALYTICS_LABELS.transactions, numeric: true },
  { key: "averageBasket", label: ANALYTICS_LABELS.averageBasket, numeric: true },
  { key: "growthPercent", label: ANALYTICS_LABELS.growth, numeric: true },
];

/** Sorts a copy; « Nouveau » (null growth) always last. */
export function sortTopStores<T extends TopStore>(stores: T[], { key, direction }: SortState): T[] {
  const sign = direction === "asc" ? 1 : -1;
  return [...stores].sort((a, b) => {
    const x = a[key];
    const y = b[key];
    if (x === null && y === null) return 0;
    if (x === null) return 1;
    if (y === null) return -1;
    if (typeof x === "number" && typeof y === "number") return (x - y) * sign;
    return String(x).localeCompare(String(y), "fr", { sensitivity: "base" }) * sign;
  });
}

function Growth({ percent }: { percent: number | null }) {
  if (percent === null) {
    return (
      <span className="inline-block text-xs font-medium bg-blue-100 text-blue-800 rounded px-2 py-0.5">
        {ANALYTICS_LABELS.newStore}
      </span>
    );
  }
  const value = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 }).format(Math.abs(percent));
  if (percent === 0) return <span className="text-gray-500">0 %</span>;
  return percent > 0 ? (
    <span className="text-green-600 font-medium">▲ +{value} %</span>
  ) : (
    <span className="text-red-600 font-medium">▼ −{value} %</span>
  );
}

/** Story 9-4 — top 20 stores by sales volume, sortable client-side (asc/desc toggle per column). */
export function TopStoresTable({ report, isLoading, isError = false, onRetry, rangeInvalid = false }: TopStoresTableProps) {
  const [sort, setSort] = useState<SortState>({ key: "gmv", direction: "desc" });
  const data = rangeInvalid ? undefined : report;
  // « # » is the GMV rank from the server order, kept whatever the client-side sort.
  const rows = useMemo(
    () => sortTopStores((data?.stores ?? []).map((s, i) => ({ ...s, rank: i + 1 })), sort),
    [data, sort],
  );

  const toggle = (key: TopStoreSortKey, numeric: boolean) =>
    setSort((s) =>
      s.key === key
        ? { key, direction: s.direction === "asc" ? "desc" : "asc" }
        : { key, direction: numeric ? "desc" : "asc" },
    );

  return (
    <section className="bg-white rounded-lg shadow p-4 space-y-3">
      <h2 className="text-sm font-semibold text-gray-600">
        {ANALYTICS_LABELS.topStoresTitle}
        {data && (
          <span className="font-normal text-gray-500">
            {" "}— du {formatIsoDay(data.from)} au {formatIsoDay(data.to)}
          </span>
        )}
      </h2>
      {data && isError && <StaleDataWarning onRetry={onRetry} />}
      {rangeInvalid ? (
        <p className="text-sm text-gray-500 bg-gray-50 rounded-lg px-4 py-3">
          Choisissez une période valide pour afficher le classement
        </p>
      ) : isLoading ? (
        <div data-testid="top-stores-skeleton" className="h-64 bg-gray-100 rounded animate-pulse" />
      ) : !data && isError ? (
        <LoadError onRetry={onRetry} />
      ) : rows.length === 0 ? (
        <p className="text-sm text-gray-400">Aucune vente sur la période</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 border-b">
                <th className="py-2 pr-3 font-medium" title="Rang par volume de ventes">#</th>
                {COLUMNS.map((c) => {
                  const active = sort.key === c.key;
                  return (
                    <th
                      key={c.key}
                      className={`py-2 pr-3 font-medium ${c.numeric ? "text-right" : ""}`}
                      aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}
                    >
                      <button
                        type="button"
                        onClick={() => toggle(c.key, c.numeric)}
                        className="hover:text-gray-800"
                      >
                        {c.label}
                        {active ? (sort.direction === "asc" ? " ▲" : " ▼") : ""}
                      </button>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.rank} className="border-b last:border-0" data-testid="top-store-row">
                  <td className="py-2 pr-3 text-gray-400">{s.rank}</td>
                  <td className="py-2 pr-3 text-gray-800">{s.storeName}</td>
                  <td className="py-2 pr-3 text-gray-700">{s.tenantName}</td>
                  <td className="py-2 pr-3 text-right text-gray-800">{formatXAFFull(s.gmv)}</td>
                  <td className="py-2 pr-3 text-right text-gray-700">{s.transactions}</td>
                  <td className="py-2 pr-3 text-right text-gray-700">{formatXAFFull(s.averageBasket)}</td>
                  <td className="py-2 pr-3 text-right">
                    <Growth percent={s.growthPercent} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
