"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { activateModule, deactivateModule } from "@/lib/api/admin-modules";
import { applyBundle } from "@/lib/api/admin-bundles";
import { consoleErrorMessage, dependentsOf, isDeactivationFailure } from "@/lib/api/console-errors";
import { moduleKeys, useBundles, useModuleCatalogue, useModuleHistory, useTenantModules } from "@/hooks/useModules";
import { moduleOriginLabel, moduleStateLabel } from "@/lib/labels";
import { formatInstantWat, todayWat } from "@/components/tenants/planDates";
import type { ActivationInput, ActivationStatus, CatalogueModule, TenantModule } from "@/types/modules";
import { trialHint, validateActivation } from "./activation";

interface TenantModulesSectionProps {
  tenantId: string;
}

type Dialog =
  | { kind: "activate"; module: TenantModule }
  | { kind: "deactivate"; module: TenantModule }
  | { kind: "pack" }
  | null;

const button = "px-2 py-1 text-xs rounded-lg font-medium disabled:opacity-50 disabled:cursor-not-allowed";

const STATE_STYLES: Record<string, string> = {
  Actif: "bg-green-100 text-green-800",
  Essai: "bg-blue-100 text-blue-800",
  "Lecture seule": "bg-orange-100 text-orange-800",
  Désactivé: "bg-gray-100 text-gray-600",
};

function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return `${formatInstantWat(iso)} ${d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit", timeZone: "Africa/Douala" })}`;
}

function names(list: { displayName: string }[]): string {
  return list.map((m) => m.displayName).join(", ");
}

/** Status + end-date fields shared by the module and pack dialogs. */
function TermsFields({
  status,
  expiresAt,
  onStatus,
  onExpiresAt,
  today,
}: {
  status: ActivationStatus;
  expiresAt: string;
  onStatus: (s: ActivationStatus) => void;
  onExpiresAt: (d: string) => void;
  today: string;
}) {
  return (
    <div className="space-y-3">
      <label className="block text-sm">
        <span className="text-gray-700 font-medium">Statut</span>
        <select
          value={status}
          onChange={(e) => onStatus(e.target.value as ActivationStatus)}
          className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
        >
          <option value="ACTIVE">Actif</option>
          <option value="TRIAL">Essai</option>
        </select>
      </label>
      <label className="block text-sm">
        <span className="text-gray-700 font-medium">Date de fin</span>
        <input
          type="date"
          min={today}
          value={expiresAt}
          onChange={(e) => onExpiresAt(e.target.value)}
          className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
        />
      </label>
      {status === "TRIAL" && !expiresAt && <p className="text-xs text-gray-500">{trialHint(today)}</p>}
    </div>
  );
}

function Modal({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
      <div role="dialog" aria-modal="true" aria-label={title} className="bg-white rounded-xl shadow-2xl p-6 max-w-md w-full mx-4">
        <h3 className="font-semibold text-gray-900 mb-3">{title}</h3>
        {children}
      </div>
    </div>
  );
}

function ErrorBox({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p role="alert" className="text-sm rounded-lg px-3 py-2 mb-3 bg-red-50 text-red-700 border border-red-200">
      {message}
    </p>
  );
}

/**
 * Story 16.4 — « Modules » section of the tenant drawer: each module with its state, end and grace dates and origin;
 * activation (status + optional end date; Essai without a date = 14 days), deactivation with the dependents
 * confirmation and a « Forcer » action after a failed shutdown, pack application, and the module history.
 */
export function TenantModulesSection({ tenantId }: TenantModulesSectionProps) {
  const queryClient = useQueryClient();
  const modules = useTenantModules(tenantId);
  const catalogue = useModuleCatalogue();
  const history = useModuleHistory(tenantId);
  const bundles = useBundles();
  const today = todayWat();

  const [dialog, setDialog] = useState<Dialog>(null);
  const [status, setStatus] = useState<ActivationStatus>("ACTIVE");
  const [expiresAt, setExpiresAt] = useState("");
  const [packId, setPackId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [dependents, setDependents] = useState<string[] | null>(null);
  const [canForce, setCanForce] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 6000);
    return () => clearTimeout(timer);
  }, [toast]);

  const byId = useMemo(() => {
    const map = new Map<string, CatalogueModule>();
    for (const m of catalogue.data ?? []) map.set(m.moduleId, m);
    return map;
  }, [catalogue.data]);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: moduleKeys.tenant(tenantId) });
    void queryClient.invalidateQueries({ queryKey: moduleKeys.history(tenantId) });
  };

  const close = () => {
    setDialog(null);
    setError(null);
    setDependents(null);
    setCanForce(false);
  };
  const open = (d: Dialog) => {
    setStatus("ACTIVE");
    setExpiresAt("");
    setPackId("");
    setError(null);
    setDependents(null);
    setCanForce(false);
    setDialog(d);
  };
  const succeeded = (message: string) => {
    close();
    refresh();
    setToast(message);
  };

  const activate = useMutation({
    mutationFn: (v: { moduleId: string; input: ActivationInput | null }) =>
      activateModule(tenantId, v.moduleId, v.input),
  });
  const deactivate = useMutation({
    mutationFn: (v: { moduleId: string; cascade: boolean; force: boolean }) =>
      deactivateModule(tenantId, v.moduleId, { cascade: v.cascade, force: v.force }),
  });
  const pack = useMutation({
    mutationFn: (v: { bundleId: string; input: ActivationInput }) => applyBundle(tenantId, v.bundleId, v.input),
  });
  // free / paid / core come from the catalogue: no action until it is loaded (wrong terms, buttons on the core)
  const catalogueReady = !!catalogue.data;
  const pending = activate.isPending || deactivate.isPending || pack.isPending || !catalogueReady;

  const isPaid = (moduleId: string) => byId.get(moduleId)?.free === false;
  const isCore = (moduleId: string) => byId.get(moduleId)?.category === "core";

  const submitActivation = (module: TenantModule) => {
    const paid = isPaid(module.moduleId);
    const input: ActivationInput = { status, expiresAt: expiresAt || null };
    const invalid = validateActivation(input, paid, today);
    if (invalid) {
      setError(invalid);
      return;
    }
    setError(null);
    activate.mutate(
      { moduleId: module.moduleId, input: paid ? input : null },
      {
        onSuccess: (r) =>
          succeeded(
            r.activated.length === 0
              ? `« ${module.displayName} » était déjà actif`
              : `Modules activés : ${names(r.activated)}`,
          ),
        onError: (err) => setError(consoleErrorMessage(err)),
      },
    );
  };

  const submitDeactivation = (module: TenantModule, cascade: boolean, force: boolean) => {
    setError(null);
    deactivate.mutate(
      { moduleId: module.moduleId, cascade, force },
      {
        onSuccess: (r) => {
          const list = r.deactivated.length > 0 ? names(r.deactivated) : module.displayName;
          const forced = r.forced.length > 0 ? ` (désactivation forcée : ${names(r.forced)})` : "";
          succeeded(`Modules désactivés : ${list}${forced}. Les données sont conservées.`);
        },
        onError: (err) => {
          const deps = dependentsOf(err);
          if (deps) {
            setDependents(deps);
            return;
          }
          setCanForce(isDeactivationFailure(err));
          setError(consoleErrorMessage(err));
        },
      },
    );
  };

  const submitPack = () => {
    if (!packId) {
      setError("Choisissez un pack");
      return;
    }
    const bundle = (bundles.data ?? []).find((b) => b.id === packId);
    const paid = bundle ? bundle.modules.some((m) => isPaid(m.moduleId)) : true;
    const input: ActivationInput = { status, expiresAt: expiresAt || null };
    const invalid = validateActivation(input, paid, today);
    if (invalid) {
      setError(invalid);
      return;
    }
    setError(null);
    pack.mutate(
      { bundleId: packId, input },
      {
        onSuccess: (r) =>
          succeeded(
            r.activated.length === 0
              ? `${r.name} : tous les modules étaient déjà actifs`
              : `${r.name} appliqué — modules activés : ${names(r.activated)}`,
          ),
        onError: (err) => setError(consoleErrorMessage(err)),
      },
    );
  };

  return (
    <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-4" aria-label="Modules">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Modules</h3>
        <button
          type="button"
          disabled={pending || !modules.data}
          onClick={() => open({ kind: "pack" })}
          className={`${button} bg-purple-600 text-white hover:bg-purple-700`}
        >
          Appliquer un pack
        </button>
      </div>

      {toast && (
        <div role="status" className="text-sm rounded-lg px-3 py-2 bg-green-50 text-green-700 border border-green-200">
          {toast}
        </div>
      )}

      {modules.isLoading && <p className="text-sm text-gray-500">Chargement des modules…</p>}
      {catalogue.isError && (
        <p className="text-sm text-red-600">
          Catalogue des modules illisible pour le moment : les actions sont indisponibles.
        </p>
      )}
      {modules.isError && (
        <p className="text-sm text-red-600">
          {consoleErrorMessage(modules.error, "Modules illisibles pour le moment")}
        </p>
      )}
      {modules.data && (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 text-xs text-gray-500 uppercase">
              <th className="text-left py-2 font-medium">Module</th>
              <th className="text-left py-2 font-medium">État</th>
              <th className="text-left py-2 font-medium">Fin</th>
              <th className="text-left py-2 font-medium">Grâce</th>
              <th className="text-left py-2 font-medium">Origine</th>
              <th className="py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {modules.data.map((m) => {
              const state = moduleStateLabel(m.state, m.status);
              return (
                <tr key={m.moduleId}>
                  <td className="py-2 text-gray-900">{m.displayName}</td>
                  <td className="py-2">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATE_STYLES[state] ?? ""}`}>
                      {state}
                    </span>
                  </td>
                  <td className="py-2 text-gray-600">{m.expiresAt ? formatInstantWat(m.expiresAt) : "—"}</td>
                  <td className="py-2 text-gray-600">{m.graceUntil ? formatInstantWat(m.graceUntil) : "—"}</td>
                  <td className="py-2 text-gray-600">{isCore(m.moduleId) ? "Socle" : moduleOriginLabel(m.origin)}</td>
                  <td className="py-2 text-right">
                    {!catalogueReady || isCore(m.moduleId) ? null : m.state === "ENABLED" ? (
                      <span className="inline-flex gap-1">
                        {isPaid(m.moduleId) && (
                          <button
                            type="button"
                            disabled={pending}
                            onClick={() => open({ kind: "activate", module: m })}
                            className={`${button} border border-gray-300 text-gray-700 hover:bg-gray-50`}
                          >
                            Prolonger
                          </button>
                        )}
                        <button
                          type="button"
                          disabled={pending}
                          onClick={() => open({ kind: "deactivate", module: m })}
                          className={`${button} border border-red-300 text-red-700 hover:bg-red-50`}
                        >
                          Désactiver
                        </button>
                      </span>
                    ) : (
                      <span className="inline-flex gap-1">
                        <button
                          type="button"
                          disabled={pending}
                          onClick={() => open({ kind: "activate", module: m })}
                          className={`${button} bg-green-600 text-white hover:bg-green-700`}
                        >
                          Activer
                        </button>
                        {m.state === "READ_ONLY" && (
                          <button
                            type="button"
                            disabled={pending}
                            onClick={() => open({ kind: "deactivate", module: m })}
                            className={`${button} border border-red-300 text-red-700 hover:bg-red-50`}
                          >
                            Désactiver
                          </button>
                        )}
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <div>
        <h4 className="text-xs font-medium text-gray-500 mb-2">Historique des modules</h4>
        {history.isLoading && <p className="text-sm text-gray-500">Chargement…</p>}
        {history.isError && <p className="text-sm text-red-600">Historique illisible pour le moment</p>}
        {history.data && history.data.length === 0 && (
          <p className="text-sm text-gray-400">Aucune action sur les modules</p>
        )}
        {history.data && history.data.length > 0 && (
          <ul className="space-y-2 max-h-64 overflow-y-auto">
            {history.data.map((h, i) => (
              <li key={`${h.occurredAt}-${i}`} className="text-sm border-l-2 border-gray-200 pl-3">
                <p className="text-gray-900">
                  <span className="font-medium">{h.action}</span>
                  {h.subject ? ` — ${h.subject}` : ""}
                </p>
                {h.detail && <p className="text-xs text-gray-600">{h.detail}</p>}
                <p className="text-xs text-gray-400">
                  {formatDateTime(h.occurredAt)}
                  {h.actorName ? ` · ${h.actorName}` : ""}
                  {h.actorPhone ? ` (${h.actorPhone})` : ""}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>

      {dialog?.kind === "activate" && (
        <Modal title={`Activer « ${dialog.module.displayName} »`}>
          <ErrorBox message={error} />
          {isPaid(dialog.module.moduleId) ? (
            <TermsFields
              status={status}
              expiresAt={expiresAt}
              onStatus={setStatus}
              onExpiresAt={setExpiresAt}
              today={today}
            />
          ) : (
            <p className="text-sm text-gray-600">Module gratuit : il est activé sans date de fin.</p>
          )}
          <div className="flex gap-3 justify-end mt-4">
            <button type="button" onClick={close} className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
              Annuler
            </button>
            <button
              type="button"
              disabled={activate.isPending}
              onClick={() => submitActivation(dialog.module)}
              className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-green-600 hover:bg-green-700 disabled:opacity-50"
            >
              Activer
            </button>
          </div>
        </Modal>
      )}

      {dialog?.kind === "deactivate" && (
        <Modal title={`Désactiver « ${dialog.module.displayName} » ?`}>
          <ErrorBox message={error} />
          {dependents ? (
            <div className="text-sm text-orange-800 bg-orange-50 border border-orange-200 rounded-lg px-3 py-2 mb-3 space-y-1">
              <p>Ce module est utilisé par d&apos;autres modules actifs. Désactiver aussi : {dependents.join(", ")}</p>
              <p>
                Les modules payants désactivés ainsi restent désactivés même quand l&apos;offre est renouvelée :
                ils deviennent des décisions du super-admin.
              </p>
            </div>
          ) : (
            <p className="text-sm text-gray-600 mb-3">
              Le module disparaît de l&apos;application. Ses données sont conservées et réapparaîtront à la
              réactivation.
            </p>
          )}
          <div className="flex flex-wrap gap-3 justify-end mt-2">
            <button type="button" onClick={close} className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
              Annuler
            </button>
            {canForce && (
              <button
                type="button"
                disabled={deactivate.isPending}
                onClick={() => submitDeactivation(dialog.module, dependents !== null, true)}
                className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-orange-600 hover:bg-orange-700 disabled:opacity-50"
              >
                Forcer
              </button>
            )}
            <button
              type="button"
              disabled={deactivate.isPending}
              onClick={() => submitDeactivation(dialog.module, dependents !== null, false)}
              className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-red-600 hover:bg-red-700 disabled:opacity-50"
            >
              {dependents ? "Tout désactiver" : "Désactiver"}
            </button>
          </div>
        </Modal>
      )}

      {dialog?.kind === "pack" && (
        <Modal title="Appliquer un pack">
          <ErrorBox message={error} />
          <div className="space-y-3">
            <label className="block text-sm">
              <span className="text-gray-700 font-medium">Pack</span>
              <select
                value={packId}
                onChange={(e) => setPackId(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
              >
                <option value="">Choisir un pack…</option>
                {(bundles.data ?? []).map((b) => (
                  <option key={b.id} value={b.id} disabled={!b.applicable}>
                    {b.name}
                    {b.applicable ? "" : " (inapplicable)"}
                  </option>
                ))}
              </select>
            </label>
            {packId && (
              <p className="text-xs text-gray-500">
                {names((bundles.data ?? []).find((b) => b.id === packId)?.modules ?? [])}
              </p>
            )}
            <TermsFields status={status} expiresAt={expiresAt} onStatus={setStatus} onExpiresAt={setExpiresAt} today={today} />
          </div>
          <div className="flex gap-3 justify-end mt-4">
            <button type="button" onClick={close} className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
              Annuler
            </button>
            <button
              type="button"
              disabled={pack.isPending}
              onClick={submitPack}
              className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
            >
              Appliquer
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
