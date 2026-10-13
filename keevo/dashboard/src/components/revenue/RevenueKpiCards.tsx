"use client";

import type { ReactNode } from "react";
import type { RevenueSummary } from "@/types/revenue";
import { formatIsoDay, formatPercent, formatXAFFull } from "@/lib/format";
import { REVENUE_LABELS } from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";

interface RevenueKpiCardsProps {
  summary: RevenueSummary | undefined;
  isLoading: boolean;
  /** Time of the last successful load, null before the first one. */
  lastUpdated: Date | null;
  onRefresh: () => void;
  isError?: boolean;
  /** A fetch is in flight (disables « Actualiser »). */
  isFetching?: boolean;
  onRetry?: () => void;
  /** The range is invalid: no query runs. */
  rangeInvalid?: boolean;
}

const CARD_COUNT = 10;

function KpiCard({ label, value, hint, children }: { label: string; value: ReactNode; hint?: string; children?: ReactNode }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <p className="text-sm text-gray-500">{label}</p>
      <p className="text-2xl font-bold mt-1 text-gray-800">{value}</p>
      {children}
      {hint && <p className="text-xs text-gray-400 mt-1">{hint}</p>}
    </div>
  );
}

function SkeletonCard() {
  return (
    <div data-testid="kpi-skeleton" className="bg-white rounded-lg shadow p-4 animate-pulse">
      <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
      <div className="h-8 bg-gray-200 rounded w-16" />
    </div>
  );
}

/** ▲ +X % (green) / ▼ −X % (red) / « — » vs the previous month. */
export function MrrTrend({ percent }: { percent: number | null }) {
  if (percent === null || percent === 0) {
    return (
      <p data-testid="mrr-trend" className="text-sm text-gray-400 mt-1">
        —
      </p>
    );
  }
  const value = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 }).format(Math.abs(percent));
  const up = percent > 0;
  return (
    <p
      data-testid="mrr-trend"
      className={`text-sm mt-1 font-medium ${up ? "text-green-600" : "text-red-600"}`}
      title="Variation par rapport au mois précédent"
    >
      {up ? `▲ +${value} %` : `▼ −${value} %`}
    </p>
  );
}

function formatTime(d: Date): string {
  return d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
}

export function RevenueKpiCards({
  summary,
  isLoading,
  lastUpdated,
  onRefresh,
  isError = false,
  isFetching = false,
  onRetry,
  rangeInvalid = false,
}: RevenueKpiCardsProps) {
  const retry = onRetry ?? onRefresh;
  const s = rangeInvalid ? undefined : summary;
  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-gray-600">
          Indicateurs
          {s && (
            <span className="font-normal text-gray-500">
              {" "}— du {formatIsoDay(s.from)} au {formatIsoDay(s.to)}
            </span>
          )}
        </h2>
        <div className="flex items-center gap-3">
          {lastUpdated && <span className="text-xs text-gray-400">Mis à jour à {formatTime(lastUpdated)}</span>}
          <button
            type="button"
            onClick={onRefresh}
            disabled={isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isFetching ? "Actualisation…" : "Actualiser"}
          </button>
        </div>
      </div>

      {s && isError && <StaleDataWarning onRetry={retry} />}

      {rangeInvalid ? (
        <p className="text-sm text-gray-500 bg-gray-50 rounded-lg px-4 py-3">
          Choisissez une période valide pour afficher les indicateurs
        </p>
      ) : isLoading ? (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
          {Array.from({ length: CARD_COUNT }).map((_, i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : !s && isError ? (
        <LoadError onRetry={retry} />
      ) : s ? (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
          <KpiCard label={REVENUE_LABELS.mrr} value={formatXAFFull(s.mrr)} hint="Aujourd'hui">
            <MrrTrend percent={s.mrrTrendPercent} />
          </KpiCard>
          <KpiCard label={REVENUE_LABELS.arr} value={formatXAFFull(s.arr)} hint="MRR × 12" />
          <KpiCard label={REVENUE_LABELS.payingTenants} value={s.payingTenants} hint="Aujourd'hui" />
          <KpiCard label={REVENUE_LABELS.freeTenants} value={s.freeTenants} hint="Aujourd'hui" />
          <KpiCard label={REVENUE_LABELS.trialTenants} value={s.trialTenants} hint="Aujourd'hui" />
          <KpiCard label={REVENUE_LABELS.premiumTenants} value={s.premiumTenants} hint="Aujourd'hui">
            {s.premiumWithoutPayment > 0 && (
              <p data-testid="premium-without-payment" className="text-xs text-amber-700 mt-1">
                {s.premiumWithoutPayment} sans paiement enregistré (non compté dans le MRR)
              </p>
            )}
          </KpiCard>
          <KpiCard
            label={REVENUE_LABELS.conversionRate}
            value={s.trialsEnded > 0 ? formatPercent(s.conversionRate) : "—"}
            hint={`${s.convertedTenants} sur ${s.trialsEnded} essai(s) terminé(s)`}
          />
          <KpiCard
            label={REVENUE_LABELS.churnRateRange}
            value={s.churnBase > 0 ? formatPercent(s.churnRate) : "—"}
            hint={`${s.churnedTenants} perdue(s) sur ${s.churnBase} payante(s)`}
          />
          <KpiCard label={REVENUE_LABELS.newPayingTenants} value={s.newPayingTenants} />
          <KpiCard label={REVENUE_LABELS.revenueCollected} value={formatXAFFull(s.revenueCollected)} />
        </div>
      ) : null}
    </section>
  );
}
