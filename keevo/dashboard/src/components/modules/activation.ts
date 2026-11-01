// Story 16.4 — client-side checks of the module / pack activation forms (the server re-checks everything).
import { addDays, formatIsoDate } from "@/components/tenants/planDates";
import type { ActivationInput, BundleInput } from "@/types/modules";

/** Length of a trial started without an end date (server rule, Story 16.1). */
export const TRIAL_DAYS = 14;

/** « Sans date : essai de 14 jours, jusqu'au JJ/MM/AAAA ». */
export function trialHint(today: string): string {
  return `Sans date : essai de ${TRIAL_DAYS} jours, jusqu'au ${formatIsoDate(addDays(today, TRIAL_DAYS))}`;
}

/**
 * French reason why an activation cannot be sent, or null.
 *
 * @param paid  whether a paid module is activated (a free module has neither status nor date)
 * @param today yyyy-MM-dd in WAT
 */
export function validateActivation(input: ActivationInput, paid: boolean, today: string): string | null {
  if (!paid) return null;
  if (input.expiresAt && !/^\d{4}-\d{2}-\d{2}$/.test(input.expiresAt)) return "Date de fin invalide";
  if (input.expiresAt && input.expiresAt < today) return "La date de fin doit être dans le futur";
  if (input.status === "ACTIVE" && !input.expiresAt) return "Une date de fin est requise pour un module payant";
  return null;
}

const BUNDLE_ID = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

/** Field errors of a pack form (French), empty when it can be sent. */
export function validateBundle(id: string, input: BundleInput): Record<string, string> {
  const errors: Record<string, string> = {};
  const slug = id.trim();
  if (!slug) errors.id = "Indiquez l'identifiant du pack";
  else if (slug.length > 50 || !BUNDLE_ID.test(slug))
    errors.id = "Minuscules, chiffres et tirets uniquement (exemple : pack-pro)";
  if (!input.name.trim()) errors.name = "Indiquez le nom du pack";
  else if (input.name.trim().length > 100) errors.name = "100 caractères au maximum";
  if (input.description.trim().length > 500) errors.description = "500 caractères au maximum";
  if (input.modules.length === 0) errors.modules = "Choisissez au moins un module";
  return errors;
}

/**
 * A plan limit typed by the super-admin: empty = unlimited (null), else a positive integer.
 *
 * @returns the value, or an error message
 */
export function parseLimit(text: string): { value: number | null; error: string | null } {
  const t = text.trim();
  if (!t) return { value: null, error: null };
  if (!/^\d+$/.test(t) || Number(t) <= 0 || Number(t) > 2_147_483_647) {
    return { value: null, error: "Nombre entier supérieur à zéro, ou vide pour illimité" };
  }
  return { value: Number(t), error: null };
}
