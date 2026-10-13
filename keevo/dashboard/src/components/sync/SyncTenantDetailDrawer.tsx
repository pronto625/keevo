"use client";

import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { getTenantSyncDetail } from "@/lib/api/admin-sync";
import { SYNC_TENANT_DETAIL_KEY } from "@/hooks/useSyncMonitoring";
import { relativeTime } from "@/lib/format";
import {
  conflictTypeLabel,
  deviceNumberLabels,
  deviceUserLabel,
  entityTypeLabel,
  operationLabel,
  planLabel,
  rejectionReasonLabel,
  strategyLabel,
} from "@/lib/labels";
import { LoadError, StaleDataWarning } from "@/components/sync/SyncErrorStates";
import type { AdminSyncTenantDetail } from "@/types/sync-monitoring";

interface SyncTenantDetailDrawerProps {
  tenantId: string | null;
  onClose: () => void;
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("fr-FR");
}

function Section({
  title,
  count,
  empty,
  children,
}: {
  title: string;
  count: number;
  empty: string;
  children: React.ReactNode;
}) {
  return (
    <section className="bg-white rounded-lg border border-gray-200 p-4">
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
        {title} ({count})
      </h3>
      {count === 0 ? <p className="text-sm text-gray-400">{empty}</p> : children}
    </section>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return (
    <th className="text-left py-2 pr-2 text-xs font-medium text-gray-500 uppercase">
      {children}
    </th>
  );
}

export function SyncTenantDetailDrawer({ tenantId, onClose }: SyncTenantDetailDrawerProps) {
  const { data, isPending, isError, refetch } = useQuery<AdminSyncTenantDetail>({
    queryKey: [SYNC_TENANT_DETAIL_KEY, tenantId],
    queryFn: () => getTenantSyncDetail(tenantId as string),
    enabled: tenantId !== null,
    staleTime: 30_000,
  });

  useEffect(() => {
    if (tenantId === null) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [tenantId, onClose]);

  if (tenantId === null) return null;

  const deviceNames = data ? deviceNumberLabels(data.devices) : [];

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/40 z-40" onClick={onClose} />

      {/* Drawer */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="sync-detail-title"
        className="fixed right-0 top-0 h-full w-[600px] max-w-full bg-white shadow-2xl z-50 flex flex-col">
        <header className="flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-white">
          <div>
            <h2 id="sync-detail-title" className="text-base font-semibold text-gray-900">
              Synchronisation — {data ? data.tenantName ?? "Sans nom" : "entreprise"}
            </h2>
            {data?.tenantPlan && (
              <p className="text-xs text-gray-500">Plan {planLabel(data.tenantPlan)}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fermer"
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none font-light"
          >
            &times;
          </button>
        </header>

        <div className="overflow-y-auto flex-1 px-6 py-5 space-y-6 bg-gray-50">
          {isPending && !isError && (
            <p className="text-sm text-gray-500 text-center py-8">Chargement…</p>
          )}
          {isError && !data && <LoadError onRetry={() => void refetch()} />}
          {isError && data && <StaleDataWarning onRetry={() => void refetch()} />}

          {data && (
            <>
              <Section title="Appareils" count={data.devices.length} empty="Aucun appareil">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <Th>Appareil</Th>
                      <Th>Utilisateur</Th>
                      <Th>Dernier push</Th>
                      <Th>Dernier pull</Th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {data.devices.map((d, i) => (
                      <tr key={`${d.deviceId}-${i}`}>
                        <td className="py-2 pr-2 text-gray-900">{deviceNames[i]}</td>
                        <td className="py-2 pr-2 text-gray-700">
                          {deviceUserLabel(d.userName, d.userRole)}
                        </td>
                        <td className="py-2 pr-2 text-gray-600" title={d.lastPushAt ?? undefined}>
                          {relativeTime(d.lastPushAt)}
                        </td>
                        <td className="py-2 pr-2 text-gray-600" title={d.lastPullAt ?? undefined}>
                          {relativeTime(d.lastPullAt)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Section>

              <Section
                title="Opérations échouées récentes"
                count={data.recentFailedOps.length}
                empty="Aucune opération échouée"
              >
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <Th>Date</Th>
                      <Th>Opération</Th>
                      <Th>Raison</Th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {data.recentFailedOps.map((op, i) => (
                      <tr key={`${op.processedAt}-${i}`}>
                        <td className="py-2 pr-2 text-gray-500 whitespace-nowrap">
                          {formatDateTime(op.processedAt)}
                        </td>
                        <td className="py-2 pr-2 text-gray-900 font-medium">{operationLabel(op.operationType)}</td>
                        <td className="py-2 pr-2 text-gray-600 break-words">
                          {rejectionReasonLabel(op.errorReason)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Section>

              <Section
                title="Conflits récents"
                count={data.recentConflicts.length}
                empty="Aucun conflit"
              >
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <Th>Date</Th>
                      <Th>Entité</Th>
                      <Th>Type</Th>
                      <Th>Stratégie</Th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {data.recentConflicts.map((c, i) => (
                      <tr key={`${c.resolvedAt}-${i}`}>
                        <td className="py-2 pr-2 text-gray-500 whitespace-nowrap">
                          {formatDateTime(c.resolvedAt)}
                        </td>
                        <td className="py-2 pr-2 text-gray-900 font-medium">{entityTypeLabel(c.entityType)}</td>
                        <td className="py-2 pr-2 text-gray-600">{conflictTypeLabel(c.conflictType)}</td>
                        <td className="py-2 pr-2 text-gray-600">{strategyLabel(c.strategy)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Section>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
