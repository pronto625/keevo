"use client";

import { useState } from "react";
import type { Bundle, BundleInput, CatalogueModule } from "@/types/modules";
import { validateBundle } from "./activation";

interface BundleFormProps {
  /** The pack edited; null for a new one (its id is then typed). */
  bundle: Bundle | null;
  /** Modules that can be part of a pack (neither core nor platform). */
  modules: CatalogueModule[];
  isPending: boolean;
  serverError: string | null;
  onSubmit: (id: string, input: BundleInput) => void;
  onCancel: () => void;
}

/** Story 16.4 — create / edit a pack: identifier (new pack only), name, description, modules. */
export function BundleForm({ bundle, modules, isPending, serverError, onSubmit, onCancel }: BundleFormProps) {
  const [id, setId] = useState(bundle?.id ?? "");
  const [name, setName] = useState(bundle?.name ?? "");
  const [description, setDescription] = useState(bundle?.description ?? "");
  const [selected, setSelected] = useState<string[]>(bundle?.modules.map((m) => m.moduleId) ?? []);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // modules of the pack no longer offered (uninstalled, now core…): shown so they can be unticked, else the
  // server refuses the pack again and again
  const offered = new Set(modules.map((m) => m.moduleId));
  const unavailable = (bundle?.modules ?? []).filter((m) => !offered.has(m.moduleId));

  const toggle = (moduleId: string) =>
    setSelected((s) => (s.includes(moduleId) ? s.filter((m) => m !== moduleId) : [...s, moduleId]));

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    // the order of selection: the pack's own modules first, then the ones ticked since
    const input: BundleInput = { name, description, modules: selected };
    const found = validateBundle(id, input);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(id.trim(), input);
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
      <form
        role="dialog"
        aria-modal="true"
        aria-label={bundle ? "Modifier le pack" : "Nouveau pack"}
        onSubmit={submit}
        className="bg-white rounded-xl shadow-2xl p-6 max-w-lg w-full mx-4 space-y-3"
      >
        <h3 className="font-semibold text-gray-900">{bundle ? `Modifier « ${bundle.name} »` : "Nouveau pack"}</h3>
        {serverError && (
          <p role="alert" className="text-sm rounded-lg px-3 py-2 bg-red-50 text-red-700 border border-red-200">
            {serverError}
          </p>
        )}
        {!bundle && (
          <label className="block text-sm">
            <span className="text-gray-700 font-medium">Identifiant</span>
            <input
              value={id}
              onChange={(e) => setId(e.target.value)}
              placeholder="pack-pro"
              className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            />
            {errors.id && <span className="text-xs text-red-600">{errors.id}</span>}
          </label>
        )}
        <label className="block text-sm">
          <span className="text-gray-700 font-medium">Nom</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          />
          {errors.name && <span className="text-xs text-red-600">{errors.name}</span>}
        </label>
        <label className="block text-sm">
          <span className="text-gray-700 font-medium">Description</span>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
            className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          />
          {errors.description && <span className="text-xs text-red-600">{errors.description}</span>}
        </label>
        <fieldset className="text-sm">
          <legend className="text-gray-700 font-medium mb-1">Modules</legend>
          <div className="grid grid-cols-2 gap-1 max-h-56 overflow-y-auto">
            {unavailable.map((m) => (
              <label key={m.moduleId} className="flex items-center gap-2 text-orange-700">
                <input type="checkbox" checked={selected.includes(m.moduleId)} onChange={() => toggle(m.moduleId)} />
                <span>Module indisponible</span>
              </label>
            ))}
            {modules.map((m) => (
              <label key={m.moduleId} className="flex items-center gap-2">
                <input type="checkbox" checked={selected.includes(m.moduleId)} onChange={() => toggle(m.moduleId)} />
                <span>
                  {m.displayName}
                  <span className="text-xs text-gray-400"> · {m.free ? "gratuit" : "payant"}</span>
                </span>
              </label>
            ))}
          </div>
          {errors.modules && <span className="text-xs text-red-600">{errors.modules}</span>}
        </fieldset>
        <div className="flex gap-3 justify-end pt-2">
          <button type="button" onClick={onCancel} className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
            Annuler
          </button>
          <button
            type="submit"
            disabled={isPending}
            className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
          >
            Enregistrer
          </button>
        </div>
      </form>
    </div>
  );
}
