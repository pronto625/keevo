"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { BundleForm } from "@/components/modules/BundleForm";
import { deleteBundle, saveBundle } from "@/lib/api/admin-bundles";
import { consoleErrorMessage } from "@/lib/api/console-errors";
import { moduleKeys, useBundles, useModuleCatalogue } from "@/hooks/useModules";
import type { Bundle, BundleInput } from "@/types/modules";

type Editing = { bundle: Bundle | null } | null;

/** Story 16.4 — « Packs »: named sets of modules applied to an account in one activation. */
export default function PacksPageClient() {
  const queryClient = useQueryClient();
  const bundles = useBundles();
  const catalogue = useModuleCatalogue();
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<Bundle | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const packable = (catalogue.data ?? []).filter((m) => m.category !== "core" && m.category !== "platform");

  const save = useMutation({
    mutationFn: (v: { id: string; input: BundleInput }) => saveBundle(v.id, v.input),
    onSuccess: (b) => {
      void queryClient.invalidateQueries({ queryKey: moduleKeys.bundles });
      setEditing(null);
      setError(null);
      setToast(`Pack « ${b.name} » enregistré`);
    },
    onError: (err) => setError(consoleErrorMessage(err)),
  });
  const remove = useMutation({
    mutationFn: (b: Bundle) => deleteBundle(b.id),
    onSuccess: (_r, b) => {
      void queryClient.invalidateQueries({ queryKey: moduleKeys.bundles });
      setDeleting(null);
      setError(null);
      setToast(`Pack « ${b.name} » supprimé`);
    },
    onError: (err) => setError(consoleErrorMessage(err)),
  });

  return (
    <AdminLayout>
      <div className="space-y-4">
        <header className="flex items-center justify-between">
          <h1 className="text-xl font-bold text-gray-800">Packs</h1>
          <button
            type="button"
            onClick={() => {
              setError(null);
              setEditing({ bundle: null });
            }}
            className="px-3 py-1.5 text-sm rounded-lg font-medium bg-purple-600 text-white hover:bg-purple-700"
          >
            Nouveau pack
          </button>
        </header>
        {toast && (
          <div role="status" className="text-sm rounded-lg px-3 py-2 bg-green-50 text-green-700 border border-green-200">
            {toast}
          </div>
        )}
        {bundles.isLoading && <p className="text-sm text-gray-500">Chargement…</p>}
        {bundles.isError && <p className="text-sm text-red-600">{consoleErrorMessage(bundles.error, "Packs illisibles pour le moment")}</p>}
        {bundles.data && bundles.data.length === 0 && <p className="text-sm text-gray-500">Aucun pack défini.</p>}
        {bundles.data && bundles.data.length > 0 && (
          <div className="bg-white rounded-lg border border-gray-200 divide-y divide-gray-100">
            {bundles.data.map((b) => (
              <div key={b.id} className="p-4 flex items-start justify-between gap-4">
                <div>
                  <p className="font-medium text-gray-900">
                    {b.name}
                    {!b.applicable && (
                      <span className="ml-2 text-xs px-2 py-0.5 rounded bg-orange-100 text-orange-800">Inapplicable</span>
                    )}
                  </p>
                  {b.description && <p className="text-sm text-gray-600">{b.description}</p>}
                  <p className="text-xs text-gray-500 mt-1">{b.modules.map((m) => m.displayName).join(", ")}</p>
                </div>
                <div className="flex gap-2 shrink-0">
                  <button
                    type="button"
                    onClick={() => {
                      setError(null);
                      setEditing({ bundle: b });
                    }}
                    className="px-2 py-1 text-xs rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50"
                  >
                    Modifier
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setError(null);
                      setDeleting(b);
                    }}
                    className="px-2 py-1 text-xs rounded-lg border border-red-300 text-red-700 hover:bg-red-50"
                  >
                    Supprimer
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {editing && (
        <BundleForm
          bundle={editing.bundle}
          modules={packable}
          isPending={save.isPending}
          serverError={error}
          onSubmit={(id, input) => save.mutate({ id, input })}
          onCancel={() => {
            setEditing(null);
            setError(null);
          }}
        />
      )}
      {deleting && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
          <div role="dialog" aria-modal="true" className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4">
            <h3 className="font-semibold text-gray-900 mb-2">Supprimer « {deleting.name} » ?</h3>
            {error && (
              <p role="alert" className="text-sm rounded-lg px-3 py-2 mb-3 bg-red-50 text-red-700 border border-red-200">
                {error}
              </p>
            )}
            <p className="text-sm text-gray-500 mb-4">Les comptes gardent leurs modules.</p>
            <div className="flex gap-3 justify-end">
              <button type="button" onClick={() => setDeleting(null)} className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
                Annuler
              </button>
              <button
                type="button"
                disabled={remove.isPending}
                onClick={() => remove.mutate(deleting)}
                className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-red-600 hover:bg-red-700 disabled:opacity-50"
              >
                Supprimer
              </button>
            </div>
          </div>
        </div>
      )}
    </AdminLayout>
  );
}
