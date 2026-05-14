"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  cancelDeletion,
  forceDelete,
  getTenantDetail,
} from "@/lib/api/admin-tenants";
import type { TenantDetail } from "@/types/tenant";

interface TenantDetailDrawerProps {
  tenantId: string;
  onClose: () => void;
}

function formatDate(iso: string | null) {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("fr-FR");
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <>
      <dt className="text-gray-500 font-medium">{label}</dt>
      <dd className="text-gray-900">{value}</dd>
    </>
  );
}

export function TenantDetailDrawer({ tenantId, onClose }: TenantDetailDrawerProps) {
  const [auditPage, setAuditPage] = useState(0);
  const [confirmAction, setConfirmAction] = useState<"cancel" | "force" | null>(null);
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery<TenantDetail>({
    queryKey: ["tenant-detail", tenantId, auditPage],
    queryFn: () => getTenantDetail(tenantId, auditPage),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelDeletion(tenantId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tenants"] });
      queryClient.invalidateQueries({ queryKey: ["tenant-detail", tenantId] });
      setConfirmAction(null);
    },
  });

  const forceMutation = useMutation({
    mutationFn: () => forceDelete(tenantId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tenants"] });
      onClose();
    },
  });

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/40 z-40" onClick={onClose} />

      {/* Drawer */}
      <aside className="fixed right-0 top-0 h-full w-[600px] bg-white shadow-2xl z-50 flex flex-col">
        {/* Header */}
        <header className="flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-white">
          <h2 className="text-base font-semibold text-gray-900">Détail tenant</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none font-light"
          >
            &times;
          </button>
        </header>

        {/* Body */}
        <div className="overflow-y-auto flex-1 px-6 py-5 space-y-6 bg-gray-50">
          {isLoading && (
            <p className="text-sm text-gray-500 text-center py-8">Chargement…</p>
          )}
          {error && (
            <p className="text-sm text-red-600 text-center py-8">
              Erreur de chargement des données
            </p>
          )}

          {data && (
            <>
              {/* Profil */}
              <section className="bg-white rounded-lg border border-gray-200 p-4">
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
                  Profil
                </h3>
                <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
                  <Row label="Code" value={<span className="font-mono text-blue-700">{data.tenant.code}</span>} />
                  <Row label="Nom" value={data.tenant.name} />
                  <Row label="Propriétaire" value={data.tenant.ownerPhone} />
                  <Row label="Plan" value={
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      data.tenant.plan === "PAID"
                        ? "bg-purple-100 text-purple-800"
                        : "bg-gray-100 text-gray-700"
                    }`}>{data.tenant.plan}</span>
                  } />
                  <Row label="Statut" value={
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      data.tenant.status === "ACTIVE"
                        ? "bg-green-100 text-green-800"
                        : data.tenant.status === "DELETION_PENDING"
                        ? "bg-amber-100 text-amber-800"
                        : "bg-red-100 text-red-800"
                    }`}>{data.tenant.status}</span>
                  } />
                  <Row label="Inscription" value={formatDate(data.tenant.registeredAt)} />
                  <Row label="Dernière activité" value={formatDate(data.tenant.lastActivityAt)} />
                  <Row label="Boutiques" value={data.tenant.storeCount} />
                  <Row label="Employés" value={data.tenant.employeeCount} />
                </dl>
              </section>

              {/* DELETION_PENDING actions */}
              {data.tenant.status === "DELETION_PENDING" && (
                <section className="bg-amber-50 border border-amber-200 rounded-lg p-4">
                  <p className="text-sm text-amber-800 font-medium mb-3">
                    ⚠️ Suppression planifiée le {formatDate(data.tenant.deletionScheduledAt)}
                  </p>
                  <div className="flex gap-3">
                    <button
                      onClick={() => setConfirmAction("cancel")}
                      className="px-4 py-2 bg-white border border-amber-400 text-amber-700 text-sm rounded-lg hover:bg-amber-50 font-medium"
                    >
                      Annuler suppression
                    </button>
                    <button
                      onClick={() => setConfirmAction("force")}
                      className="px-4 py-2 bg-red-600 text-white text-sm rounded-lg hover:bg-red-700 font-medium"
                    >
                      Supprimer maintenant
                    </button>
                  </div>
                </section>
              )}

              {/* Boutiques */}
              <section className="bg-white rounded-lg border border-gray-200 p-4">
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
                  Boutiques ({data.stores.length})
                </h3>
                {data.stores.length === 0 ? (
                  <p className="text-sm text-gray-400">Aucune boutique</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Nom</th>
                        <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Type</th>
                        <th className="text-right py-2 text-xs font-medium text-gray-500 uppercase">CA (XAF)</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {data.stores.map((s) => (
                        <tr key={s.id}>
                          <td className="py-2 text-gray-900 font-medium">{s.name}</td>
                          <td className="py-2 text-gray-500">{s.type}</td>
                          <td className="py-2 text-right text-gray-900">
                            {s.totalRevenue.toLocaleString("fr-FR")}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>

              {/* Employés */}
              <section className="bg-white rounded-lg border border-gray-200 p-4">
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
                  Employés ({data.employees.length})
                </h3>
                {data.employees.length === 0 ? (
                  <p className="text-sm text-gray-400">Aucun employé</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Nom</th>
                        <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Rôle</th>
                        <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Dernière connexion</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {data.employees.map((emp) => (
                        <tr key={emp.id}>
                          <td className="py-2 text-gray-900 font-medium">{emp.name}</td>
                          <td className="py-2 text-gray-500">{emp.role}</td>
                          <td className="py-2 text-gray-600">{formatDate(emp.lastLoginAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>

              {/* Journal d'audit */}
              <section className="bg-white rounded-lg border border-gray-200 p-4">
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">
                  Journal d&apos;audit ({data.auditLog.total} événements)
                </h3>
                {data.auditLog.events.length === 0 ? (
                  <p className="text-sm text-gray-400">Aucun événement</p>
                ) : (
                  <>
                    <div className="space-y-1 max-h-72 overflow-y-auto">
                      {data.auditLog.events.map((ev) => (
                        <div key={ev.id} className="text-xs py-2 border-b border-gray-100 flex gap-2">
                          <span className="text-gray-400 shrink-0">
                            {formatDate(ev.occurredAt)}
                          </span>
                          <span className="text-gray-700 font-medium">{ev.eventType}</span>
                          {ev.details && (
                            <span className="text-gray-500 truncate">— {ev.details}</span>
                          )}
                        </div>
                      ))}
                    </div>
                    {data.auditLog.total > 50 && (
                      <div className="flex gap-2 mt-3">
                        <button
                          onClick={() => setAuditPage((p) => Math.max(0, p - 1))}
                          disabled={auditPage === 0}
                          className="text-xs px-3 py-1.5 border border-gray-300 rounded text-gray-700 disabled:opacity-40 hover:bg-gray-50"
                        >
                          ← Précédent
                        </button>
                        <button
                          onClick={() => setAuditPage((p) => p + 1)}
                          disabled={(auditPage + 1) * 50 >= data.auditLog.total}
                          className="text-xs px-3 py-1.5 border border-gray-300 rounded text-gray-700 disabled:opacity-40 hover:bg-gray-50"
                        >
                          Suivant →
                        </button>
                      </div>
                    )}
                  </>
                )}
              </section>
            </>
          )}
        </div>
      </aside>

      {/* Confirmation dialog */}
      {confirmAction && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
          <div className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4">
            <h3 className="font-semibold text-gray-900 mb-2">
              {confirmAction === "cancel" ? "Annuler la suppression ?" : "Supprimer maintenant ?"}
            </h3>
            <p className="text-sm text-gray-500 mb-4">
              {confirmAction === "cancel"
                ? "Le tenant sera remis en statut ACTIVE."
                : "Cette action est irréversible. Le tenant sera immédiatement supprimé."}
            </p>
            <div className="flex gap-3 justify-end">
              <button
                onClick={() => setConfirmAction(null)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                onClick={() =>
                  confirmAction === "cancel"
                    ? cancelMutation.mutate()
                    : forceMutation.mutate()
                }
                disabled={cancelMutation.isPending || forceMutation.isPending}
                className={`px-4 py-2 text-sm text-white rounded-lg font-medium disabled:opacity-50 ${
                  confirmAction === "force"
                    ? "bg-red-600 hover:bg-red-700"
                    : "bg-blue-600 hover:bg-blue-700"
                }`}
              >
                Confirmer
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

