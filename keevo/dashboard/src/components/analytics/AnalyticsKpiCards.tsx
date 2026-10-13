"use client";

import type { AnalyticsOverview } from "@/types/analytics";
import { formatPercent, formatXAFFull } from "@/lib/format";
import { ANALYTICS_LABELS } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface AnalyticsKpiCardsProps {
  overview: AnalyticsOverview | undefined;
  isLoading: boolean;
  isError?: boolean;
  onRetry?: () => void;
}

const CARD_COUNT = 9;
const NUMBER = new Intl.NumberFormat("fr-FR");

function KpiCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <p className="text-sm text-gray-500">{label}</p>
      <p className="text-2xl font-bold mt-1 text-gray-800">{value}</p>
      {hint && <p className="text-xs text-gray-400 mt-1">{hint}</p>}
    </div>
  );
}

/** Story 9-4 — platform KPIs: active users, engagement rate, entreprises, stores, products, sales, GMV. */
export function AnalyticsKpiCards({ overview: o, isLoading, isError = false, onRetry }: AnalyticsKpiCardsProps) {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
        {Array.from({ length: CARD_COUNT }).map((_, i) => (
          <div key={i} data-testid="kpi-skeleton" className="bg-white rounded-lg shadow p-4 animate-pulse">
            <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
            <div className="h-8 bg-gray-200 rounded w-16" />
          </div>
        ))}
      </div>
    );
  }
  if (!o) return isError ? <LoadError onRetry={onRetry} /> : null;
  return (
    <div className="space-y-3">
      {isError && <StaleDataWarning onRetry={onRetry} />}
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
        <KpiCard label={ANALYTICS_LABELS.dau} value={NUMBER.format(o.dau)} />
        <KpiCard label={ANALYTICS_LABELS.mau} value={NUMBER.format(o.mau)} />
        <KpiCard
          label={ANALYTICS_LABELS.dauMau}
          value={o.dauMauRatio === null ? "—" : formatPercent(o.dauMauRatio)}
          hint="Part des utilisateurs du mois actifs aujourd'hui"
        />
        <KpiCard label={ANALYTICS_LABELS.totalTenants} value={NUMBER.format(o.totalTenants)} />
        <KpiCard label={ANALYTICS_LABELS.totalStores} value={NUMBER.format(o.totalStores)} />
        <KpiCard
          label={ANALYTICS_LABELS.activeStores}
          value={NUMBER.format(o.activeStores)}
          hint="Au moins une vente sur 30 jours"
        />
        <KpiCard label={ANALYTICS_LABELS.totalProducts} value={NUMBER.format(o.totalProducts)} />
        <KpiCard label={ANALYTICS_LABELS.totalCompletedSales} value={NUMBER.format(o.totalCompletedSales)} />
        <KpiCard label={ANALYTICS_LABELS.totalGmv} value={formatXAFFull(o.totalGmv)} hint="Depuis le lancement" />
      </div>
    </div>
  );
}
