"use client";

import { useState } from "react";
import { limitLabel } from "@/lib/labels";
import type { CatalogueModule, PlanProfile, PlanProfileInput } from "@/types/modules";
import { parseLimit } from "./activation";

interface PlanProfileCardProps {
  profile: PlanProfile;
  /** Installed paid modules (the only ones a profile can grant). */
  paidModules: CatalogueModule[];
  isPending: boolean;
  serverError: string | null;
  onSave: (input: PlanProfileInput) => void;
}

const LIMITS = [
  { key: "maxStores", label: "Boutiques" },
  { key: "maxProducts", label: "Produits" },
  { key: "maxEmployees", label: "Employés" },
] as const;

type LimitKey = (typeof LIMITS)[number]["key"];

function text(value: number | null): string {
  return value === null ? "" : String(value);
}

/** Story 16.4 — one plan profile: limits (empty = illimité) and the paid modules it grants. */
export function PlanProfileCard({ profile, paidModules, isPending, serverError, onSave }: PlanProfileCardProps) {
  const [limits, setLimits] = useState<Record<LimitKey, string>>({
    maxStores: text(profile.maxStores),
    maxProducts: text(profile.maxProducts),
    maxEmployees: text(profile.maxEmployees),
  });
  // granted modules no longer paid / installed cannot be granted any more: dropped, and said so
  const offered = new Set(paidModules.map((m) => m.moduleId));
  const dropped = profile.modules.filter((m) => !offered.has(m.id));
  const [modules, setModules] = useState<string[]>(profile.modules.map((m) => m.id).filter((id) => offered.has(id)));
  const [confirming, setConfirming] = useState<PlanProfileInput | null>(null);
  const [errors, setErrors] = useState<Partial<Record<LimitKey, string>>>({});

  const toggle = (id: string) => setModules((s) => (s.includes(id) ? s.filter((m) => m !== id) : [...s, id]));

  const save = (e: React.FormEvent) => {
    e.preventDefault();
    const parsed = Object.fromEntries(LIMITS.map((l) => [l.key, parseLimit(limits[l.key])])) as Record<
      LimitKey,
      ReturnType<typeof parseLimit>
    >;
    const found: Partial<Record<LimitKey, string>> = {};
    for (const l of LIMITS) if (parsed[l.key].error) found[l.key] = parsed[l.key].error as string;
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const input: PlanProfileInput = {
      maxStores: parsed.maxStores.value,
      maxProducts: parsed.maxProducts.value,
      maxEmployees: parsed.maxEmployees.value,
      modules,
    };
    const before = profile.modules.map((m) => m.id).sort().join(",");
    if ([...modules].sort().join(",") !== before) {
      setConfirming(input); // the accounts of the offer are re-synced and their owners told: ask first
      return;
    }
    onSave(input);
  };

  return (
    <form onSubmit={save} aria-label={`Offre ${profile.label}`} className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold text-gray-900">{profile.label}</h2>
        <span className="text-xs text-gray-500">
          {LIMITS.map((l) => `${l.label} : ${limitLabel(profile[l.key])}`).join(" · ")}
        </span>
      </div>
      {profile.fallback && (
        <p role="alert" className="text-sm rounded-lg px-3 py-2 bg-orange-50 text-orange-800 border border-orange-200">
          Profil enregistré introuvable ou illisible : les valeurs par défaut s&apos;appliquent. Enregistrez-le pour
          le rétablir.
        </p>
      )}
      {dropped.length > 0 && (
        <p className="text-sm rounded-lg px-3 py-2 bg-orange-50 text-orange-800 border border-orange-200">
          Ne sont plus des modules payants proposés et seront retirés à l&apos;enregistrement :{" "}
          {dropped.map((m) => m.displayName).join(", ")}
        </p>
      )}
      {serverError && (
        <p role="alert" className="text-sm rounded-lg px-3 py-2 bg-red-50 text-red-700 border border-red-200">
          {serverError}
        </p>
      )}
      <div className="grid grid-cols-3 gap-3">
        {LIMITS.map((l) => (
          <label key={l.key} className="block text-sm">
            <span className="text-gray-700 font-medium">{l.label}</span>
            <input
              inputMode="numeric"
              value={limits[l.key]}
              placeholder="illimité"
              onChange={(e) => setLimits((s) => ({ ...s, [l.key]: e.target.value }))}
              className="mt-1 block w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            />
            {errors[l.key] && <span className="text-xs text-red-600">{errors[l.key]}</span>}
          </label>
        ))}
      </div>
      <fieldset className="text-sm">
        <legend className="text-gray-700 font-medium mb-1">Modules payants inclus</legend>
        <div className="grid grid-cols-2 gap-1">
          {paidModules.map((m) => (
            <label key={m.moduleId} className="flex items-center gap-2">
              <input type="checkbox" checked={modules.includes(m.moduleId)} onChange={() => toggle(m.moduleId)} />
              <span>{m.displayName}</span>
            </label>
          ))}
        </div>
      </fieldset>
      {confirming && (
        <div role="alertdialog" aria-label="Confirmer la mise à jour des comptes"
          className="text-sm rounded-lg px-3 py-2 bg-yellow-50 text-yellow-900 border border-yellow-300 space-y-2">
          <p>
            Tous les comptes de l&apos;offre « {profile.label} » seront mis à jour et leurs propriétaires seront
            prévenus des modules désactivés. Continuer ?
          </p>
          <div className="flex gap-2 justify-end">
            <button type="button" onClick={() => setConfirming(null)}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">
              Annuler
            </button>
            <button type="button" disabled={isPending}
              onClick={() => {
                const input = confirming;
                setConfirming(null);
                onSave(input);
              }}
              className="px-3 py-1.5 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50">
              Continuer
            </button>
          </div>
        </div>
      )}
      <div className="flex justify-end">
        <button
          type="submit"
          disabled={isPending}
          className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
        >
          Enregistrer
        </button>
      </div>
    </form>
  );
}
