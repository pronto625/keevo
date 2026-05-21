"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { SyncOverviewCards } from "@/components/sync/SyncOverviewCards";
import { SyncHealthTable } from "@/components/sync/SyncHealthTable";
import { SyncTenantDetailDrawer } from "@/components/sync/SyncTenantDetailDrawer";
import { useSyncMonitoring } from "@/hooks/useSyncMonitoring";

export default function SyncMonitoringPage() {
  const {
    overviewQuery,
    healthQuery,
    selectedTenantId,
    setSelectedTenantId,
    refresh,
  } = useSyncMonitoring();

  return (
    <AdminLayout>
      <div className="p-6 space-y-6">
        <h1 className="text-xl font-bold text-gray-800">
          Synchronisation &amp; Santé Devices
        </h1>

        <SyncOverviewCards
          data={overviewQuery.data}
          isLoading={overviewQuery.isLoading}
          onRefresh={refresh}
        />

        <SyncHealthTable
          data={healthQuery.data}
          isLoading={healthQuery.isLoading}
          onRowClick={(id) => setSelectedTenantId(id)}
          selectedTenantId={selectedTenantId}
        />
      </div>

      <SyncTenantDetailDrawer
        tenantId={selectedTenantId}
        onClose={() => setSelectedTenantId(null)}
      />
    </AdminLayout>
  );
}
