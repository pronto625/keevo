"use client";

import { useRef, useState, type KeyboardEvent } from "react";
import { useIsFetching } from "@tanstack/react-query";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { AnalyticsKpiCards } from "@/components/analytics/AnalyticsKpiCards";
import { GrowthCharts } from "@/components/analytics/GrowthCharts";
import { GeographySection } from "@/components/analytics/GeographySection";
import { RetentionTable } from "@/components/analytics/RetentionTable";
import { TopStoresTable } from "@/components/analytics/TopStoresTable";
import { AdoptionChart } from "@/components/analytics/AdoptionChart";
import { DateRangeFilter } from "@/components/revenue/DateRangeFilter";
import {
  ANALYTICS_KEY,
  useAdoption,
  useAnalyticsOverview,
  useAnalyticsRefresh,
  useRetention,
  useTopStores,
} from "@/hooks/useAnalytics";
import { ANALYTICS_TABS } from "@/lib/labels";

type TabId = (typeof ANALYTICS_TABS)[number]["id"];

function OverviewTab() {
  const { overviewQuery, growthQuery, geographyQuery } = useAnalyticsOverview();
  return (
    <div className="space-y-6">
      <AnalyticsKpiCards
        overview={overviewQuery.data}
        isLoading={overviewQuery.isLoading}
        isError={overviewQuery.isError}
        onRetry={() => void overviewQuery.refetch()}
      />
      <GrowthCharts
        data={growthQuery.data}
        isLoading={growthQuery.isLoading}
        isError={growthQuery.isError}
        onRetry={() => void growthQuery.refetch()}
      />
      <GeographySection
        data={geographyQuery.data}
        isLoading={geographyQuery.isLoading}
        isError={geographyQuery.isError}
        onRetry={() => void geographyQuery.refetch()}
      />
    </div>
  );
}

function RetentionTab() {
  const query = useRetention();
  return (
    <RetentionTable
      report={query.data}
      isLoading={query.isLoading}
      isError={query.isError}
      onRetry={() => void query.refetch()}
    />
  );
}

function TopStoresTab() {
  const { query, from, setFrom, to, setTo, resetRange, rangeError } = useTopStores();
  return (
    <div className="space-y-6">
      <DateRangeFilter
        from={from}
        to={to}
        onFromChange={setFrom}
        onToChange={setTo}
        onReset={resetRange}
        error={rangeError}
        resetLabel="30 derniers jours"
      />
      <TopStoresTable
        report={query.data}
        isLoading={query.isLoading}
        isError={query.isError}
        onRetry={() => void query.refetch()}
        rangeInvalid={rangeError !== null}
      />
    </div>
  );
}

function AdoptionTab() {
  const query = useAdoption();
  return (
    <AdoptionChart
      report={query.data}
      isLoading={query.isLoading}
      isError={query.isError}
      onRetry={() => void query.refetch()}
    />
  );
}

/** Story 9-4 — platform analytics: overview, cohort retention, top stores, feature adoption. */
export default function AnalyticsPageClient() {
  const [tab, setTab] = useState<TabId>("overview");
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);

  // ArrowLeft / ArrowRight (wrapping), Home / End: move focus and selection (roving tabIndex).
  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = ANALYTICS_TABS.length - 1;
    const next =
      event.key === "ArrowRight" ? (index === last ? 0 : index + 1)
      : event.key === "ArrowLeft" ? (index === 0 ? last : index - 1)
      : event.key === "Home" ? 0
      : event.key === "End" ? last
      : null;
    if (next === null) return;
    event.preventDefault();
    setTab(ANALYTICS_TABS[next].id);
    tabRefs.current[next]?.focus();
  };
  const refresh = useAnalyticsRefresh();
  const isFetching = useIsFetching({ queryKey: [ANALYTICS_KEY] }) > 0;

  return (
    <AdminLayout>
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-bold text-gray-800">Statistiques</h1>
          <button
            type="button"
            onClick={refresh}
            disabled={isFetching}
            className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isFetching ? "Actualisation…" : "Actualiser"}
          </button>
        </div>

        <div role="tablist" aria-label="Sections" className="flex gap-1 border-b border-gray-200">
          {ANALYTICS_TABS.map((t, i) => (
            <button
              key={t.id}
              ref={(el) => {
                tabRefs.current[i] = el;
              }}
              id={`analytics-tab-${t.id}`}
              type="button"
              role="tab"
              aria-selected={tab === t.id}
              aria-controls="analytics-tabpanel"
              tabIndex={tab === t.id ? 0 : -1}
              onClick={() => setTab(t.id)}
              onKeyDown={(e) => onTabKeyDown(e, i)}
              className={`px-4 py-2 text-sm -mb-px border-b-2 ${
                tab === t.id
                  ? "border-blue-600 text-blue-700 font-medium"
                  : "border-transparent text-gray-500 hover:text-gray-800"
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div role="tabpanel" id="analytics-tabpanel" aria-labelledby={`analytics-tab-${tab}`} tabIndex={0}>
          {tab === "overview" && <OverviewTab />}
          {tab === "retention" && <RetentionTab />}
          {tab === "top-stores" && <TopStoresTab />}
          {tab === "adoption" && <AdoptionTab />}
        </div>
      </div>
    </AdminLayout>
  );
}
