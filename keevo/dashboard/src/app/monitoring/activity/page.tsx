"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { OperationsKpiCards } from "@/components/operations/OperationsKpiCards";
import { PeriodSelector } from "@/components/operations/PeriodSelector";
import { GmvTrendChart } from "@/components/operations/GmvTrendChart";
import { TenantActivityTable } from "@/components/operations/TenantActivityTable";
import { InactiveTenantsSection } from "@/components/operations/InactiveTenantsSection";
import { useOperations } from "@/hooks/useOperations";

export default function ActivityPage() {
  const {
    overviewQuery,
    activityQuery,
    trendQuery,
    inactiveQuery,
    period,
    setPeriod,
    customFrom,
    setCustomFrom,
    customTo,
    setCustomTo,
    page,
    setPage,
    refresh,
    rangeIncomplete,
  } = useOperations();

  // Last successful load, derived from React Query (no setState during render).
  const lastUpdated = overviewQuery.dataUpdatedAt
    ? new Date(overviewQuery.dataUpdatedAt)
    : null;

  const handleRefresh = () => {
    refresh();
  };

  const isFetching =
    overviewQuery.isFetching ||
    trendQuery.isFetching ||
    activityQuery.isFetching ||
    inactiveQuery.isFetching;

  return (
    <AdminLayout>
      <div className="p-6 space-y-6">
        <h1 className="text-xl font-bold text-gray-800">
          Activité Opérationnelle
        </h1>

        <PeriodSelector
          period={period}
          onPeriodChange={(p) => {
            setPeriod(p);
            setPage(0);
          }}
          customFrom={customFrom}
          customTo={customTo}
          onCustomFromChange={setCustomFrom}
          onCustomToChange={setCustomTo}
        />

        <OperationsKpiCards
          overview={overviewQuery.data}
          isLoading={overviewQuery.isLoading}
          lastUpdated={lastUpdated}
          onRefresh={handleRefresh}
          isError={overviewQuery.isError}
          isFetching={isFetching}
          onRetry={() => void overviewQuery.refetch()}
          rangeIncomplete={rangeIncomplete}
        />

        <GmvTrendChart
          data={trendQuery.data}
          isLoading={trendQuery.isLoading}
          isError={trendQuery.isError}
          onRetry={() => void trendQuery.refetch()}
          rangeIncomplete={rangeIncomplete}
        />

        <TenantActivityTable
          data={activityQuery.data}
          isLoading={activityQuery.isLoading}
          page={page}
          setPage={setPage}
          isError={activityQuery.isError}
          onRetry={() => void activityQuery.refetch()}
          rangeIncomplete={rangeIncomplete}
        />

        <InactiveTenantsSection
          data={inactiveQuery.data}
          isLoading={inactiveQuery.isLoading}
          isError={inactiveQuery.isError}
          onRetry={() => void inactiveQuery.refetch()}
        />
      </div>
    </AdminLayout>
  );
}
