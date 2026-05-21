"use client";

import { useState } from "react";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { OperationsKpiCards } from "@/components/operations/OperationsKpiCards";
import { PeriodSelector } from "@/components/operations/PeriodSelector";
import { GmvTrendChart } from "@/components/operations/GmvTrendChart";
import { TenantActivityTable } from "@/components/operations/TenantActivityTable";
import { InactiveTenantsSection } from "@/components/operations/InactiveTenantsSection";
import { useOperations } from "@/hooks/useOperations";

export default function ActivityPage() {
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

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
  } = useOperations();

  const handleRefresh = () => {
    refresh();
    setLastUpdated(new Date());
  };

  // Track last successful load time
  if (overviewQuery.isSuccess && !lastUpdated) {
    setLastUpdated(new Date());
  }

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
        />

        <GmvTrendChart
          data={trendQuery.data}
          isLoading={trendQuery.isLoading}
        />

        <TenantActivityTable
          data={activityQuery.data}
          isLoading={activityQuery.isLoading}
          page={page}
          setPage={setPage}
        />

        <InactiveTenantsSection data={inactiveQuery.data} />
      </div>
    </AdminLayout>
  );
}
